package org.nbscn.varvis.variantanalysis.provider;

import htsjdk.samtools.seekablestream.SeekableFileStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.BlockCompressedInputStream;
import htsjdk.tribble.index.tabix.TabixIndex;
import htsjdk.tribble.readers.TabixReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Local, exact-coordinate transport for the hash-pinned publisher index. No executable or network. */
final class RevelLocalIndexQuery {
    static final String METHOD = "HTSJDK_LOCAL_REVEL_POINT_LOOKUP";
    static final String VERSION = "1.0.0";
    static final int MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
    record Result(String output, long compressedBytes) { }

    Result query(Path data, Path index, String region, Duration timeout) throws IOException {
        return query(data, index, region, timeout, System::nanoTime);
    }

    Result query(Path data, Path index, String region, Duration timeout, LongSupplier clock) throws IOException {
        RevelPublisherIndexManifest.require(region.matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT):[1-9][0-9]{0,8}-[1-9][0-9]{0,8}"), "REVEL_QUERY_REGION_INVALID");
        String[] fields = region.split("[:-]");
        RevelPublisherIndexManifest.require(fields[1].equals(fields[2]), "REVEL_QUERY_POINT_REQUIRED");
        RevelPublisherIndexManifest.require(timeout.toMillis() >= 1 && timeout.toMillis() <= 30000, "REVEL_QUERY_TIMEOUT_INVALID");
        long deadline = clock.getAsLong() + timeout.toNanos();
        check(deadline, clock);
        RevelPublisherIndexManifest.require(Files.size(index) <= 64 * 1024 * 1024, "REVEL_INDEX_SIZE_LIMIT");
        // This index has already passed the manifest SHA gate. Own its stream if parsing fails.
        try (var input = new BlockCompressedInputStream(index.toFile())) { new TabixIndex(input); }
        check(deadline, clock);
        try (var stream = new LimitedStream(data, deadline, clock);
             var reader = new TabixReader(data.toString(), index.toString(), stream)) {
            StringBuilder output = new StringBuilder();
            var iterator = reader.query(region);
            String line; int rows = 0;
            while (iterator != null && (line = iterator.next()) != null) {
                check(deadline, clock);
                RevelPublisherIndexManifest.require(++rows <= 1000 && line.length() <= 16512
                        && output.length() + line.length() + 1 <= BoundedTabixProcessRunner.MAX_STDOUT, "REVEL_QUERY_ROW_LIMIT_OR_ENCODING_INVALID");
                output.append(line).append('\n');
            }
            check(deadline, clock);
            return new Result(output.toString(), stream.bytes);
        }
    }

    private static void check(long deadline, LongSupplier clock) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("REVEL_QUERY_INTERRUPTED");
        if (clock.getAsLong() - deadline >= 0) throw new IOException("REVEL_QUERY_TIMEOUT");
    }

    /** Deadline checks surround local file operations; no abandoned worker or background process. */
    private static final class LimitedStream extends SeekableStream {
        private final SeekableFileStream file;
        private final long deadline;
        private final LongSupplier clock;
        private long bytes;
        LimitedStream(Path data, long deadline, LongSupplier clock) throws IOException {
            this.file = new SeekableFileStream(data.toFile()); this.deadline = deadline; this.clock = clock;
        }
        @Override public long length() { return file.length(); }
        @Override public long position() throws IOException { return file.position(); }
        @Override public void seek(long position) throws IOException { check(deadline, clock); file.seek(position); check(deadline, clock); }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            check(deadline, clock);
            RevelPublisherIndexManifest.require(bytes + len <= MAX_COMPRESSED_BYTES, "REVEL_QUERY_COMPRESSED_BYTE_LIMIT");
            int n = file.read(b, off, len); if (n > 0) bytes += n;
            check(deadline, clock); return n;
        }
        @Override public boolean eof() throws IOException { return file.eof(); }
        @Override public String getSource() { return file.getSource(); }
        @Override public void close() throws IOException { file.close(); }
    }
}
