package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Bounded HGVS coding edits on an already reference-verified model. Equivalence is
 * confined to one coding exon; this is not whole-genome normalization or pathogenicity. */
final class Pvs1StartLossReferenceMapping {
    private static final Pattern EDIT = Pattern.compile("([1-9][0-9]*)(?:_([1-9][0-9]*))?(?:([ACGT])>([ACGT])|(delins|del|dup|ins)([ACGT]*))");
    private static final Pattern INTRON_SNV = Pattern.compile("([1-9][0-9]*)([+-])([1-9][0-9]*)([ACGT])>([ACGT])");
    private static final Pattern INTRON_DELETION = Pattern.compile("([1-9][0-9]*)([+-])([1-9][0-9]*)(?:_([1-9][0-9]*)([+-])([1-9][0-9]*))?del");
    record IntronicSpan(int first, int last, String reason) {
        boolean resolved() { return reason.isEmpty(); }
    }
    private record IntronPoint(int position, long left, long right) { }
    record IntronicLocation(int position, String reason) {
        boolean resolved() { return reason.isEmpty(); }
    }
    record Result(String key, int first, int last, String reason,
                  String alteredCodingExon, int exonCdsStart, int exonCdsEnd) {
        Result(String key, int first, int last, String reason) { this(key, first, last, reason, "", 0, 0); }
        boolean mapped() { return reason.isEmpty(); }
    }
    private Pvs1StartLossReferenceMapping() { }
    private static Result bad(String reason) { return new Result("", 0, 0, reason); }

    /** Scope-only proof for exact-transcript intronic SNVs. This does not verify
     * the intronic REF, splicing effect or pathogenicity. Every description of
     * this exact transcript must agree; uncertain edits stay unresolved. */
    static IntronicLocation intronicLocation(JsonNode model, JsonNode descriptions) {
        String prefix = model.path("transcript").asText() + ":c.";
        if (!descriptions.isArray() || descriptions.isEmpty() || descriptions.size() > 1000
                || !model.path("exons").isArray() || model.path("exons").size() > 1000
                || !"GRCh38".equals(model.path("assembly").asText())
                || !model.path("strand").isIntegralNumber() || Math.abs(model.path("strand").asInt()) != 1)
            return new IntronicLocation(0, "REFERENCE_INTRON_LOCATION_UNRESOLVED");
        String identity = "", genomicEdit = ""; int position = 0;
        try {
            for (var description : descriptions) {
                if (!description.isTextual()) throw new IllegalArgumentException();
                if (!description.asText().startsWith(prefix)) continue;
                var match = INTRON_SNV.matcher(description.asText().substring(prefix.length()));
                if (!match.matches() || match.group(4).equals(match.group(5))) throw new IllegalArgumentException();
                int anchor = Integer.parseInt(match.group(1)), offset = Integer.parseInt(match.group(3));
                boolean plus = "+".equals(match.group(2));
                long mapped = intronPoint(model, anchor, plus, offset).position();
                String next = mapped + ":" + match.group(4) + ":" + match.group(5);
                if (!identity.isEmpty() && !identity.equals(next))
                    return new IntronicLocation(0, "REFERENCE_REPRESENTATION_CONFLICT");
                identity = next; position = (int) mapped;
                genomicEdit = "g." + mapped + CodingTranscriptMapping.orientAllele(model, match.group(4))
                        + ">" + CodingTranscriptMapping.orientAllele(model, match.group(5));
            }
            for (var description : descriptions) {
                String[] parts = description.asText().split(":", 2);
                String contig = PublishedCriterionKnowledge.grch38PrimaryContig(parts[0]);
                if (!contig.isEmpty() && (!contig.equals(model.path("contig").asText())
                        || parts.length != 2 || !genomicEdit.equals(parts[1])))
                    return new IntronicLocation(0, "REFERENCE_REPRESENTATION_CONFLICT");
            }
            return position > 0 ? new IntronicLocation(position, "") : new IntronicLocation(0, "EXACT_TRANSCRIPT_MAPPING_UNRESOLVED");
        } catch (IllegalArgumentException invalid) { return new IntronicLocation(0, "REFERENCE_INTRON_LOCATION_UNRESOLVED"); }
    }

    /** A scope-only deletion proof requires the entire span to lie in one
     * intron, plus an agreeing native GRCh38 genomic description. This does not
     * establish REF sequence, normalization equivalence, or splice harmlessness. */
    static IntronicSpan intronicDeletion(JsonNode model, JsonNode descriptions) {
        String prefix = model.path("transcript").asText() + ":c.";
        if (!descriptions.isArray() || descriptions.isEmpty() || descriptions.size() > 1000
                || !model.path("exons").isArray() || model.path("exons").size() > 1000
                || !"GRCh38".equals(model.path("assembly").asText())
                || !model.path("strand").isIntegralNumber() || Math.abs(model.path("strand").asInt()) != 1)
            return new IntronicSpan(0, 0, "REFERENCE_INTRON_LOCATION_UNRESOLVED");
        int first = 0, last = 0; boolean genomicConfirmed = false;
        try {
            for (var description : descriptions) {
                if (!description.isTextual() || description.asText().length() > 300) throw new IllegalArgumentException();
                if (!description.asText().startsWith(prefix)) continue;
                var m = INTRON_DELETION.matcher(description.asText().substring(prefix.length()));
                if (!m.matches()) throw new IllegalArgumentException();
                var start = intronPoint(model, Integer.parseInt(m.group(1)), "+".equals(m.group(2)), Integer.parseInt(m.group(3)));
                var end = m.group(4) == null ? start : intronPoint(model, Integer.parseInt(m.group(4)), "+".equals(m.group(5)), Integer.parseInt(m.group(6)));
                long length = (long) (end.position() - start.position()) * model.path("strand").asInt() + 1;
                if (start.left() != end.left() || start.right() != end.right() || length < 1 || length > 50
                        || m.group(4) != null && length == 1) throw new IllegalArgumentException();
                int lo = Math.min(start.position(), end.position()), hi = Math.max(start.position(), end.position());
                if (first != 0 && (lo != first || hi != last)) return new IntronicSpan(0, 0, "REFERENCE_REPRESENTATION_CONFLICT");
                first = lo; last = hi;
            }
            if (first == 0) return new IntronicSpan(0, 0, "EXACT_TRANSCRIPT_MAPPING_UNRESOLVED");
            String genomicEdit = "g." + first + (first == last ? "" : "_" + last) + "del";
            for (var description : descriptions) {
                String[] parts = description.asText().split(":", 2);
                String contig = PublishedCriterionKnowledge.grch38PrimaryContig(parts[0]);
                if (contig.isEmpty()) continue;
                if (!contig.equals(model.path("contig").asText()) || parts.length != 2 || !genomicEdit.equals(parts[1]))
                    return new IntronicSpan(0, 0, "REFERENCE_REPRESENTATION_CONFLICT");
                genomicConfirmed = true;
            }
            return genomicConfirmed ? new IntronicSpan(first, last, "")
                    : new IntronicSpan(0, 0, "REFERENCE_INTRON_GENOMIC_CONFIRMATION_REQUIRED");
        } catch (IllegalArgumentException invalid) { return new IntronicSpan(0, 0, "REFERENCE_INTRON_LOCATION_UNRESOLVED"); }
    }

    private static IntronPoint intronPoint(JsonNode model, int anchor, boolean plus, int offset) {
        int strand = model.path("strand").asInt();
        JsonNode before = null, after = null;
        for (var exon : model.path("exons")) {
            for (String field : new String[]{"cdsStart", "cdsEnd", "genomicStart", "genomicEnd"})
                if (!exon.path(field).isIntegralNumber() || !exon.path(field).canConvertToInt()) throw new IllegalArgumentException();
            if (exon.path("cdsEnd").asLong() == (plus ? anchor : (long) anchor - 1)) {
                if (before != null) throw new IllegalArgumentException(); before = exon;
            }
            if (exon.path("cdsStart").asLong() == (plus ? (long) anchor + 1 : anchor)) {
                if (after != null) throw new IllegalArgumentException(); after = exon;
            }
        }
        if (before == null || after == null) throw new IllegalArgumentException();
        long left = strand == 1 ? before.path("genomicEnd").asLong() : after.path("genomicEnd").asLong();
        long right = strand == 1 ? after.path("genomicStart").asLong() : before.path("genomicStart").asLong();
        long origin = plus ? (strand == 1 ? left : right) : (strand == 1 ? right : left);
        long mapped = origin + (plus ? (long) strand * offset : -(long) strand * offset);
        if (mapped <= left || mapped >= right || mapped < 1 || mapped > Integer.MAX_VALUE) throw new IllegalArgumentException();
        for (var exon : model.path("exons"))
            if (mapped >= exon.path("genomicStart").asLong() && mapped <= exon.path("genomicEnd").asLong()) throw new IllegalArgumentException();
        return new IntronPoint((int) mapped, left, right);
    }

    /** Converts a bounded forward-strand VCF edit to the same verified coding
     * haplotype identity used for HGVS. Cross-exon/UTR anchors stay unresolved. */
    static Result genomic(JsonNode model, int position, String reference, String alternate) {
        if (position < 1 || reference == null || alternate == null
                || !reference.matches("[ACGT]{1,50}") || !alternate.matches("[ACGT]{1,50}")
                || reference.equals(alternate) || (long) position + reference.length() - 1 > Integer.MAX_VALUE)
            return bad("REFERENCE_EDIT_UNSUPPORTED");
        int end = position + reference.length() - 1;
        JsonNode exon = null;
        for (var row : model.path("exons")) if (position >= row.path("genomicStart").asInt()
                && end <= row.path("genomicEnd").asInt()) {
            if (exon != null) return bad("REFERENCE_MAPPING_INVALID");
            exon = row;
        }
        if (exon == null) return bad("REFERENCE_EXON_BOUNDARY_UNRESOLVED");
        int a = CodingTranscriptMapping.cdsPosition(model, position), b = CodingTranscriptMapping.cdsPosition(model, end);
        int start = Math.min(a, b), last = Math.max(a, b);
        String cds = model.path("cdsSequence").asText();
        if (start < 1 || last > cds.length() || last - start + 1 != reference.length())
            return bad("REFERENCE_MAPPING_INVALID");
        String ref = CodingTranscriptMapping.orientAllele(model, reference);
        String alt = CodingTranscriptMapping.orientAllele(model, alternate);
        if (!cds.substring(start - 1, last).equals(ref)) return bad("REFERENCE_SEQUENCE_CONFLICT");
        String edit = start + (start == last ? "" : "_" + last) + "delins" + alt;
        return map(model, model.path("transcript").asText() + ":c." + edit);
    }

    static Result map(JsonNode model, String hgvs) {
        String prefix = model.path("transcript").asText() + ":c.";
        if (!hgvs.startsWith(prefix) || hgvs.length() > 300)
            return bad("EXACT_TRANSCRIPT_MAPPING_UNRESOLVED");
        var match = EDIT.matcher(hgvs.substring(prefix.length()));
        if (!match.matches()) return bad("REFERENCE_EDIT_UNSUPPORTED");
        int start, end;
        try {
            start = Integer.parseInt(match.group(1));
            end = match.group(2) == null ? start : Integer.parseInt(match.group(2));
        } catch (NumberFormatException ex) { return bad("REFERENCE_MAPPING_INVALID"); }
        String cds = model.path("cdsSequence").asText();
        if (start < 1 || end < start || end > cds.length() || end - start >= 50
                || (match.group(2) != null && start == end)) return bad("REFERENCE_MAPPING_INVALID");
        String ref = cds.substring(start - 1, end), inserted;
        int offset = start - 1, removed = end - start + 1;
        String kind = match.group(5);
        if (kind == null) {
            if (start != end || !ref.equals(match.group(3))) return bad("REFERENCE_SEQUENCE_CONFLICT");
            inserted = match.group(4);
        } else {
            inserted = match.group(6);
            if (inserted.length() > 50) return bad("REFERENCE_EDIT_UNSUPPORTED");
            if ((kind.equals("del") || kind.equals("dup")) && !inserted.isEmpty() && !ref.equals(inserted))
                return bad("REFERENCE_SEQUENCE_CONFLICT");
            switch (kind) {
                case "del" -> inserted = "";
                case "dup" -> { inserted = ref; offset = end; removed = 0; }
                case "ins" -> {
                    if (end != start + 1 || inserted.isEmpty()) return bad("REFERENCE_MAPPING_INVALID");
                    offset = start; removed = 0;
                }
                case "delins" -> { if (inserted.isEmpty()) return bad("REFERENCE_MAPPING_INVALID"); }
                default -> { return bad("REFERENCE_EDIT_UNSUPPORTED"); }
            }
        }
        JsonNode exon = null;
        // Original described span and both insertion flanks must be in one exon.
        int requiredEnd = removed == 0 ? offset + 1 : end;
        for (var item : model.path("exons")) {
            if (start >= item.path("cdsStart").asInt() && requiredEnd <= item.path("cdsEnd").asInt()) {
                if (exon != null) return bad("REFERENCE_MAPPING_INVALID");
                exon = item;
            }
        }
        if (exon == null) return bad("REFERENCE_EXON_BOUNDARY_UNRESOLVED");
        int lo = Math.max(1, exon.path("cdsStart").asInt()), hi = Math.min(cds.length(), exon.path("cdsEnd").asInt());
        String original = cds.substring(lo - 1, hi);
        int local = offset - lo + 1;
        String altered = original.substring(0, local) + inserted + original.substring(local + removed);
        if (original.equals(altered)) return bad("REFERENCE_SEQUENCE_CONFLICT");
        // Trim shared bases before computing all equivalent placements within this exon.
        String deleted = original.substring(local, local + removed);
        while (!deleted.isEmpty() && !inserted.isEmpty() && deleted.charAt(0) == inserted.charAt(0)) {
            local++; deleted = deleted.substring(1); inserted = inserted.substring(1);
        }
        while (!deleted.isEmpty() && !inserted.isEmpty() && deleted.charAt(deleted.length() - 1) == inserted.charAt(inserted.length() - 1)) {
            deleted = deleted.substring(0, deleted.length() - 1); inserted = inserted.substring(0, inserted.length() - 1);
        }
        int prefixLength = 0, suffixLength = 0, n = original.length(), a = altered.length();
        while (prefixLength < Math.min(n, a) && original.charAt(prefixLength) == altered.charAt(prefixLength)) prefixLength++;
        while (suffixLength < Math.min(n, a) && original.charAt(n - suffixLength - 1) == altered.charAt(a - suffixLength - 1)) suffixLength++;
        int first = Math.max(0, n - deleted.length() - suffixLength);
        int last = Math.min(n - deleted.length(), prefixLength);
        if (deleted.length() != inserted.length() && (first == 0 || last + deleted.length() == n))
            return bad("REFERENCE_NORMALIZATION_BOUNDARY_UNRESOLVED");
        String identity = model.path("assembly").asText() + ":" + model.path("contig").asText() + ":"
                + exon.path("genomicStart").asInt() + ":" + exon.path("genomicEnd").asInt() + ":" + lo + ":" + hi
                + ":" + model.path("strand").asInt() + ":" + original + ":" + altered;
        // Insertions affect the gap after CDS first+lo-1; using its right base is conservative at restart.
        return new Result(hash(identity), first + lo, last + lo + Math.max(1, deleted.length()) - 1, "", altered, lo, hi);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
