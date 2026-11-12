package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Complete equivalent simple-edit envelope; no alignment is preferred or clipped to a region. */
final class Bp3EquivalentEditEnvelope {
    private Bp3EquivalentEditEnvelope() { }

    /** Minimal replacement facts for the explicitly enabled complex path; pure indels retain all placements. */
    static String resolveComplex(ObjectNode model, ObjectNode observation) {
        String reference = observation.path("referenceCds").asText(), altered = observation.path("alteredCds").asText();
        int n = reference.length(), m = altered.length(), delta = m - n;
        if (n == 0 || m == 0 || delta == 0 || delta % 3 != 0) return "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
        int prefix = 0, suffix = 0;
        while (prefix < Math.min(n, m) && reference.charAt(prefix) == altered.charAt(prefix)) prefix++;
        while (suffix < Math.min(n, m) - prefix && reference.charAt(n - 1 - suffix) == altered.charAt(m - 1 - suffix)) suffix++;
        int deleted = n - prefix - suffix, inserted = m - prefix - suffix;
        if (deleted == 0 || inserted == 0) {
            int physicalOrdinal = observation.path("exonOrdinal").asInt();
            if (physicalOrdinal < 1 || physicalOrdinal > model.path("exons").size()) return "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
            var physicalExon = model.path("exons").path(physicalOrdinal - 1);
            int physicalStart = observation.path("referenceEditStart0").asInt(), physicalEnd = observation.path("referenceEditEnd0").asInt();
            if (physicalStart < physicalEnd && (physicalStart + 1 < physicalExon.path("cdsStart").asInt()
                    || physicalEnd > physicalExon.path("cdsEnd").asInt())) return "BP3_EQUIVALENT_ENVELOPE_EXON_BOUNDARY";
            return resolve(model, observation);
        }
        int end = prefix + deleted;
        int proteinStart = prefix / 3 + 1, proteinEnd = (end + 2) / 3;
        observation.putObject("equivalentEditEnvelope").put("algorithmId", "complete-cds-minimal-replacement-envelope-v1")
                .put("coordinateSystem", "REFERENCE_CDS_ZERO_BASED_HALF_OPEN")
                .put("firstStart0", prefix).put("lastStart0", prefix).put("equivalentPositionCount", 1)
                .put("deletedLength", deleted).put("insertedLength", inserted)
                .put("proteinStart", proteinStart).put("proteinEnd", proteinEnd)
                .put("proteinCoordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE");
        if (prefix < 3 || end > n - 3 || proteinStart < 1 || proteinEnd > model.path("proteinLength").asInt())
            return "BP3_EQUIVALENT_ENVELOPE_ENGINEERING_BOUNDARY";
        int ordinal = observation.path("exonOrdinal").asInt();
        if (ordinal < 1 || ordinal > model.path("exons").size()) return "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
        var exon = model.path("exons").path(ordinal - 1);
        // Keep the physical event inside its validated exon; CDS equivalence cannot move a genomic deletion across an intron.
        if (prefix + 1 < exon.path("cdsStart").asInt() || end > exon.path("cdsEnd").asInt()
                || observation.path("referenceEditStart0").asInt() + 1 < exon.path("cdsStart").asInt()
                || observation.path("referenceEditEnd0").asInt() > exon.path("cdsEnd").asInt())
            return "BP3_EQUIVALENT_ENVELOPE_EXON_BOUNDARY";
        String reconstructed = reference.substring(0, prefix) + altered.substring(prefix, prefix + inserted) + reference.substring(end);
        return reconstructed.equals(altered) ? null : "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
    }

    static String resolve(ObjectNode model, ObjectNode observation) {
        String reference = observation.path("referenceCds").asText(), altered = observation.path("alteredCds").asText();
        int n = reference.length(), m = altered.length(), delta = m - n;
        if (n == 0 || m == 0 || delta == 0 || delta % 3 != 0) return "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
        int prefix = 0, suffix = 0;
        while (prefix < Math.min(n, m) && reference.charAt(prefix) == altered.charAt(prefix)) prefix++;
        // Deliberately independent of prefix: repetitive identical parts may overlap.
        while (suffix < Math.min(n, m) && reference.charAt(n - 1 - suffix) == altered.charAt(m - 1 - suffix)) suffix++;
        int first = (delta > 0 ? n : m) - suffix, last = prefix;
        if (first < 0 || last < first) return "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
        int affectedStart = delta > 0 ? (first + 2) / 3 : first / 3 + 1;
        int affectedEnd = delta > 0 ? last / 3 + 1 : (last - delta + 2) / 3;
        observation.putObject("equivalentEditEnvelope").put("algorithmId", "complete-cds-simple-edit-envelope-v1")
                .put("coordinateSystem", "REFERENCE_CDS_ZERO_BASED_HALF_OPEN")
                .put("firstStart0", first).put("lastStart0", last).put("equivalentPositionCount", last - first + 1)
                .put("deletedLength", Math.max(0, -delta)).put("proteinStart", affectedStart).put("proteinEnd", affectedEnd)
                .put("proteinCoordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE");
        // Every position must satisfy the same engineering bounds and the original supported exon.
        int ordinal = observation.path("exonOrdinal").asInt();
        if (ordinal < 1 || ordinal > model.path("exons").size()) return "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
        var exon = model.path("exons").path(ordinal - 1);
        for (int k = first; k <= last; k++) {
            int end = delta > 0 ? k : k - delta;
            if (k < 3 || end > n - 3 || affectedStart < 1 || affectedEnd > model.path("proteinLength").asInt())
                return "BP3_EQUIVALENT_ENVELOPE_ENGINEERING_BOUNDARY";
            // Include the VCF anchor and insertion flanks on the appropriate strand.
            int lo = delta > 0 ? k : model.path("strand").asInt() == 1 ? k : k + 1;
            int hi = delta > 0 ? k + 1 : model.path("strand").asInt() == 1 ? end : end + 1;
            if (lo < exon.path("cdsStart").asInt() || hi > exon.path("cdsEnd").asInt())
                return "BP3_EQUIVALENT_ENVELOPE_EXON_BOUNDARY";
        }
        // Prefix/suffix bounds enumerate all solutions; independently reconstruct both extreme edits.
        for (int k : new int[]{first, last}) {
            String reconstructed = delta > 0
                    ? reference.substring(0, k) + altered.substring(k, k + delta) + reference.substring(k)
                    : reference.substring(0, k) + reference.substring(k - delta);
            if (!altered.equals(reconstructed)) return "BP3_EQUIVALENT_ENVELOPE_UNDETERMINED";
        }
        return null;
    }
}
