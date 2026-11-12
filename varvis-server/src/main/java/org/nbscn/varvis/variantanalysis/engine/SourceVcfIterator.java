package org.nbscn.varvis.variantanalysis.engine;

import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.tribble.readers.LineIterator;
import htsjdk.variant.bcf2.BCF2Codec;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.*;
import java.io.*;
import java.util.zip.GZIPInputStream;

/** The existing HTSJDK codec and stream path, with one transient line available before decode.
 * Some sample orders force eager genotype decoding; inspecting VariantContext alone loses FT.
 * No alternative VCF parser, extra file read, or collection of sample rows is introduced.
 */
final class SourceVcfIterator implements AutoCloseable {
    record Row(VariantContext variant, String rawGenotypes) { }
    private final InputStream input;
    private final VCFCodec codec;
    private final LineIterator lines;
    private final VCFIterator binary;
    private final VCFHeader header;
    SourceVcfIterator(InputStream raw) throws IOException {
        BufferedInputStream stream = new BufferedInputStream(raw, Math.max(BCF2Codec.SIZEOF_BCF_HEADER, 8000));
        if (IOUtil.isGZIPInputStream(stream)) stream = new BufferedInputStream(new GZIPInputStream(stream), BCF2Codec.SIZEOF_BCF_HEADER);
        input = stream;
        if (BCF2Codec.tryReadBCFVersion(stream) != null) {
            binary = new VCFIteratorBuilder().open(stream); codec = null; lines = null; header = binary.getHeader();
        } else {
            binary = null; codec = new VCFCodec(); lines = codec.makeSourceFromStream(stream);
            header = (VCFHeader) codec.readActualHeader(lines);
        }
    }
    VCFHeader getHeader() { return header; }
    boolean hasNext() { return binary != null ? binary.hasNext() : java.util.Objects.requireNonNull(lines).hasNext(); }
    Row next() {
        if (binary != null) return new Row(binary.next(), null);
        String line = java.util.Objects.requireNonNull(lines).next();
        int tab = -1;
        for (int i = 0; i < 8; i++) { tab = line.indexOf('\t', tab + 1); if (tab < 0) break; }
        String raw = tab < 0 ? null : line.substring(tab + 1);
        return new Row(java.util.Objects.requireNonNull(codec).decode(line), raw);
    }
    @Override public void close() { CloserUtil.close(binary); CloserUtil.close(lines); CloserUtil.close(input); }
}
