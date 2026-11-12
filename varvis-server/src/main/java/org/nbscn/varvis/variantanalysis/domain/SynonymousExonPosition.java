package org.nbscn.varvis.variantanalysis.domain;

/** Same-record VEP 116 NearestExonJB observation. Genomic positions are 1-based;
 * distances are zero-based and oriented along the selected transcript. */
public record SynonymousExonPosition(String assembly, String contig, long position,
        String reference, String alternate, String transcriptId, String exonId,
        int strand, long exonStart, long exonEnd, String rawNearestExonJb,
        String annotationVersion, String pluginSha256) {
    public static final String PLUGIN_SHA256 = "8789d689c7929fca77a0d742a3cb8c9b1a2986d217686e623a3c9e549888026a";
    public static final String POLICY = "VEP116_NEAREST_EXON_JB_SYNONYMOUS_V1";
    public SynonymousExonPosition {
        if (!"GRCh38".equals(assembly) || contig == null || contig.isBlank()
                || reference == null || !reference.matches("[ACGT]")
                || alternate == null || !alternate.matches("[ACGT]") || reference.equals(alternate)
                || transcriptId == null || !transcriptId.matches("ENST[0-9]+\\.[1-9][0-9]*")
                || exonId == null || !exonId.matches("ENSE[0-9]+(?:\\.[0-9]+)?")
                || (strand != 1 && strand != -1) || exonStart < 1 || exonEnd < exonStart
                || position < exonStart || position > exonEnd || exonEnd - exonStart > Integer.MAX_VALUE
                || rawNearestExonJb == null || !"116.0".equals(annotationVersion)
                || !PLUGIN_SHA256.equals(pluginSha256))
            throw new IllegalArgumentException("SYNONYMOUS_POSITION_PROVENANCE_INVALID");
        long left = position - exonStart, right = exonEnd - position;
        String nearest = left == right ? "start_end" : left < right ? "start" : "end";
        String expected = exonId + "|" + Math.min(left, right) + "|" + nearest + "|" + (exonEnd - exonStart + 1);
        if (!expected.equals(rawNearestExonJb))
            throw new IllegalArgumentException("SYNONYMOUS_POSITION_OBSERVATION_CONFLICT");
    }
    public int distanceFromStart() { return (int)(strand == 1 ? position - exonStart : exonEnd - position); }
    public int distanceFromEnd() { return (int)(strand == 1 ? exonEnd - position : position - exonStart); }
    public boolean matches(NormalizedVariantKey key, String selectedTranscript) {
        return key != null && assembly.equals(key.getGenomeAssembly())
                && position == key.getPosition() && reference.equals(key.getReference()) && alternate.equals(key.getAlternate())
                && normalizeContig(contig).equals(normalizeContig(key.getChromosome())) && transcriptId.equals(selectedTranscript);
    }
    private static String normalizeContig(String value) {
        return value != null && value.startsWith("chr") ? value.substring(3) : value == null ? "" : value;
    }
}
