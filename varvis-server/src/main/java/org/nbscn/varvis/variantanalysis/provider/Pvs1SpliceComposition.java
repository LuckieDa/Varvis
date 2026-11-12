package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;

/** Source-bound adapter to A5; no independent sequence editor or DNA-to-CDS mapper. */
final class Pvs1SpliceComposition {
    static final String SCHEMA = "pvs1-splice-coding-composition-v1";
    private Pvs1SpliceComposition() { }

    record Composition(CodingTranscriptEdits.Result result, ObjectNode snapshot,
                       boolean retainedCodingEdit, boolean explicitlyReviewed,
                       int referenceEventStart0, int referenceEventEnd0, int nativeStopStart1) { }

    static Composition compose(ObjectMapper mapper, JsonNode context, JsonNode model, JsonNode fact,
                               int start0, int end0) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var allele = context.at("/variant/evidenceQueryAllele");
        require(allele.equals(fact.path("allele")), "ALLELE_IDENTITY_MISMATCH");
        String cds = model.path("cdsSequence").asText();
        String alleleHash = adapter.canonicalSha256(allele), eventHash = adapter.canonicalSha256(fact.path("event"));
        var reference = CodingTranscriptEdits.bind(mapper, context, model,
                fact.path("transcriptModelSha256").asText(), sha(cds));
        requireIntronTopology(model, allele);
        var declaration = fact.path("codingEditComposition");
        boolean reviewed = !declaration.isMissingNode();
        if (reviewed) validateDeclaration(declaration, fact.path("sourceRecords"), alleleHash, eventHash, sha(cds));
        var splice = new CodingTranscriptEdits.Edit("splice:" + eventHash, CodingTranscriptEdits.Origin.SPLICE_PROJECTION,
                start0, end0, "", cds.substring(start0, end0), adapter.canonicalSha256(fact), alleleHash, 0);
        var supplied = new ArrayList<CodingTranscriptEdits.Edit>();
        supplied.add(splice);
        CodingTranscriptEdits.Edit coding = null;
        try {
            coding = CodingTranscriptEdits.projectAllele(reference, allele, alleleHash);
        } catch (CodingTranscriptEdits.Rejected rejected) {
            if (!"CODING_EDIT_GENOMIC_BOUNDARY_REQUIRES_SPLICE_RESULT".equals(rejected.reasonCode())) throw rejected;
            int position = allele.path("position").asInt();
            String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
            var footprint = CodingTranscriptEdits.genomicInterval(reference, position, position + ref.length() - 1);
            // A wholly intronic source-bound deletion outcome has no coding component.
            // An insertion at a coding flank is ambiguous even when its anchor is intronic.
            int footprintEnd = position + ref.length() - 1;
            if (ref.length() == 1 && alt.length() > 1) footprintEnd = position + 1;
            boolean touchesRnaExon = false;
            for (var exon : model.path("exons")) if (position <= exon.path("genomicEnd").asInt()
                    && footprintEnd >= exon.path("genomicStart").asInt()) touchesRnaExon = true;
            // UTR bases are absent from A5's coding blocks too. They must not be
            // mistaken for intronic sequence whose qualified RNA outcome is known.
            if (!footprint.blocks().isEmpty() || touchesRnaExon) throw rejected;
        }
        if (coding != null) {
            supplied.add(coding);
            if (reviewed && declaration.has("codingHgvsc")) {
                var hgvs = CodingTranscriptEdits.codingHgvsEdit(reference, declaration.path("codingHgvsc").asText(), alleleHash);
                require(CodingTranscriptEdits.sequenceEquivalent(reference, coding, hgvs), "CODING_HGVS_ALLELE_MISMATCH");
                require(coding.referenceEditStart0() == hgvs.referenceEditStart0()
                        && coding.referenceEditEnd0() == hgvs.referenceEditEnd0()
                        && coding.insertedSequence().equals(hgvs.insertedSequence()), "CODING_HGVS_SPLICE_PLACEMENT_AMBIGUOUS");
                // Retain both source representations. The core deduplicates exact aliases
                // and rejects overlaps; repeat-shifted equivalence is not enough to select
                // a favorable side of the RNA deletion boundary.
                supplied.add(hgvs);
            }
        } else require(!reviewed || !declaration.has("codingHgvsc"), "CODING_HGVS_WITHOUT_MAPPED_ALLELE");
        var result = CodingTranscriptEdits.apply(reference, supplied);
        var snapshot = result.snapshot(mapper);
        boolean retained = false;
        int eventStart = start0, eventEnd = end0;
        for (var row : snapshot.path("edits")) {
            if (!"CODING_ALLELE".equals(row.path("origin").asText()) || !"APPLIED".equals(row.path("status").asText())) continue;
            retained = true;
            int lo = row.path("referenceEditStart0").asInt(), hi = row.path("referenceEditEnd0").asInt();
            require(lo >= 3, "START_CODON_EDIT_NOT_IMPLEMENTED");
            require(hi <= cds.length() - 3 && !(lo == hi && lo > cds.length() - 3), "NATIVE_STOP_EDIT_NOT_IMPLEMENTED");
            eventStart = Math.min(eventStart, lo == hi ? lo - 1 : lo);
            eventEnd = Math.max(eventEnd, lo == hi ? hi + 1 : hi);
        }
        if (retained && !reviewed) throw new IllegalArgumentException("PVS1_SPLICE_RETAINED_CODING_ALLELE_NOT_SUPPORTED");
        if (reviewed && "COMPLETE_ALLELIC_CDS".equals(declaration.path("sequenceScope").asText()))
            require(result.alteredCds().equals(declaration.path("alteredCdsSequence").asText()), "COMPLETE_ALLELIC_CDS_MISMATCH");
        int nativeStop = result.mapReferenceBoundary(cds.length() - 3, CodingTranscriptEdits.Bias.AFTER) + 1;
        require(nativeStop > 0 && result.alteredToReferenceBase(nativeStop) == cds.length() - 2
                && result.alteredToReferenceBase(nativeStop + 1) == cds.length() - 1
                && result.alteredToReferenceBase(nativeStop + 2) == cds.length(), "NATIVE_STOP_MAPPING_UNRESOLVED");
        snapshot.put("compositionStatus", retained ? "SAME_ALLELE_CODING_AND_SPLICE_EDITS_APPLIED"
                : coding == null ? "NO_CODING_COMPONENT" : "CODING_COMPONENT_REMOVED_BY_SPLICE")
                .put("explicitCompositionReviewed", reviewed)
                .put("rnaSequenceScope", reviewed ? declaration.path("sequenceScope").asText() : "REFERENCE_CDS_DELETION")
                .put("completeAllelicCdsWitnessVerified", reviewed && "COMPLETE_ALLELIC_CDS".equals(declaration.path("sequenceScope").asText()))
                .put("fullRnaOrEjcProofGranted", false);
        if (reviewed) snapshot.set("sourceDeclaration", declaration.deepCopy());
        return new Composition(result, snapshot, retained, reviewed, eventStart, eventEnd, nativeStop);
    }

    private static void validateDeclaration(JsonNode declaration, JsonNode sources, String alleleHash, String eventHash, String cdsHash) {
        require(only(declaration, Set.of("schemaVersion", "status", "reviewId", "basis", "sourceIds", "alleleGroupSha256",
                "referenceCdsSha256", "spliceEventSha256", "sequenceScope", "alteredCdsSequence", "codingHgvsc"))
                && SCHEMA.equals(declaration.path("schemaVersion").asText())
                && "REVIEWED_FOR_SAME_ALLELE_COMPOSITION".equals(declaration.path("status").asText())
                && text(declaration.path("reviewId")) && sourced(declaration, sources), "NOT_REVIEWED");
        require(alleleHash.equals(declaration.path("alleleGroupSha256").asText()), "SAME_ALLELE_BINDING_MISMATCH");
        require(eventHash.equals(declaration.path("spliceEventSha256").asText()), "SPLICE_EVENT_BINDING_MISMATCH");
        require(cdsHash.equals(declaration.path("referenceCdsSha256").asText()), "REFERENCE_CDS_BINDING_MISMATCH");
        String scope = declaration.path("sequenceScope").asText();
        require(Set.of("REFERENCE_CDS_DELETION", "COMPLETE_ALLELIC_CDS").contains(scope), "SEQUENCE_SCOPE_INVALID");
        require("COMPLETE_ALLELIC_CDS".equals(scope) ? declaration.path("alteredCdsSequence").isTextual()
                && declaration.path("alteredCdsSequence").asText().matches("[ACGT]{1,2000000}")
                : !declaration.has("alteredCdsSequence"), "CDS_WITNESS_INVALID");
        require(!declaration.has("codingHgvsc") || text(declaration.path("codingHgvsc")), "CODING_HGVS_INVALID");
    }

    /** The coding-only core cannot establish whether a genomic intron/EJC still
     * exists. Check full RNA exon boundaries even when both flanks are untranslated.
     * For complex replacements the full reference footprint is a conservative bound;
     * no alternate alignment is invented to retain a favorable junction.
     */
    private static void requireIntronTopology(JsonNode model, JsonNode allele) {
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        if (ref.length() <= 1) return;
        long first = allele.path("position").asLong(), last = first + ref.length() - 1;
        if (alt.length() == 1 && ref.startsWith(alt)) first++;
        var exons = model.path("exons");
        for (int i = 1; i < exons.size(); i++) {
            var left = exons.path(i - 1);var right = exons.path(i);
            long lo = Math.min(left.path("genomicEnd").asLong(), right.path("genomicEnd").asLong()) + 1;
            long hi = Math.max(left.path("genomicStart").asLong(), right.path("genomicStart").asLong()) - 1;
            require(lo > hi || first > lo || last < hi, "GENOMIC_JUNCTION_TOPOLOGY_UNSUPPORTED");
        }
    }

    private static boolean sourced(JsonNode value, JsonNode sources) {
        if (!text(value.path("basis")) || !value.path("sourceIds").isArray() || value.path("sourceIds").isEmpty()) return false;
        var seen = new java.util.HashSet<String>();
        for (var id : value.path("sourceIds")) {
            if (!text(id) || !seen.add(id.asText())) return false;
            boolean found = false;
            for (var source : sources) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }
    private static boolean only(JsonNode value, Set<String> fields) {
        if (!value.isObject()) return false;
        var names = value.fieldNames();while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
    private static void require(boolean ok, String code) { if (!ok) throw new IllegalArgumentException("PVS1_SPLICE_COMPOSITION_" + code); }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
