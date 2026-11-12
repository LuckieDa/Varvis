package org.nbscn.varvis.variantanalysis.persistence;

import java.nio.charset.StandardCharsets;
import java.io.*;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.DigestInputStream;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.*;

/** Lossless storage encoding only; public JSON and its hash always use the original bytes. */
final class AcmgStoredJson {
    private static final String PREFIX = "VARVIS_GZIP_JSON_V1:";
    // Multi-background results include complete source originals. Bound decoded memory
    // separately from the much smaller compressed database representation.
    static final int MAX_RAW = 512 * 1024 * 1024;
    static final int MAX_STORED = 128 * 1024 * 1024;
    private AcmgStoredJson() { }
    static String encode(String json) {
        if (json == null) return null;
        if (json.length() > MAX_RAW) throw new IllegalStateException("ACMG_STORED_JSON_SIZE_LIMIT");
        if (json.length() < 262144 && json.getBytes(StandardCharsets.UTF_8).length < 262144) return json;
        try {
            var bytes = new ByteArrayOutputStream();
            var digest = MessageDigest.getInstance("SHA-256");
            // Stream UTF-8 into gzip rather than allocating another entire raw byte array.
            var compressed = new LimitedOutputStream(bytes, (MAX_STORED - 128L) / 4 * 3);
            var raw = new LimitedOutputStream(new DigestOutputStream(new GZIPOutputStream(compressed), digest), MAX_RAW);
            try (var writer = new OutputStreamWriter(raw, StandardCharsets.UTF_8)) {
                for (int offset = 0; offset < json.length(); offset += 8192)
                    writer.write(json, offset, Math.min(8192, json.length() - offset));
            }
            return PREFIX + raw.count + ":" + HexFormat.of().formatHex(digest.digest()) + ":" + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (SizeLimit failure) { throw new IllegalStateException("ACMG_STORED_JSON_SIZE_LIMIT", failure);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible);
        } catch (IOException failure) { throw new IllegalStateException("ACMG_STORED_JSON_ENCODE_FAILED", failure); }
    }
    static String decode(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            if (stored.length() > MAX_STORED) throw new IOException("stored size limit");
            String[] parts = stored.substring(PREFIX.length()).split(":", 3);
            if (parts.length != 3) throw new IOException("invalid envelope");
            int size = Integer.parseInt(parts[0]);
            if (size < 0 || size > MAX_RAW || !parts[1].matches("[a-f0-9]{64}"))
                throw new IOException("invalid envelope");
            var digest = MessageDigest.getInstance("SHA-256");
            var text = new StringBuilder();
            // Never preallocate from untrusted envelope length. Check actual UTF-8 bytes
            // while decoding, including when a corrupt envelope understates its size.
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(parts[2])))) {
                var bounded = new LimitedInputStream(new DigestInputStream(gzip, digest), size);
                var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
                var reader = new InputStreamReader(bounded, decoder);
                char[] chunk = new char[8192]; int count;
                while ((count = reader.read(chunk)) != -1) text.append(chunk, 0, count);
                if (bounded.count != size) throw new IOException("length mismatch");
            }
            if (!parts[1].equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("digest mismatch");
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible);
        } catch (IOException | RuntimeException failure) { throw new IllegalStateException("ACMG_STORED_JSON_INTEGRITY_FAILED", failure); }
    }

    private static final class SizeLimit extends IOException { }
    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit; private long count;
        LimitedOutputStream(OutputStream out, long limit) { super(out); this.limit = limit; }
        @Override public void write(int b) throws IOException { if (++count > limit) throw new SizeLimit(); out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if ((count += len) > limit) throw new SizeLimit(); out.write(b, off, len);
        }
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit; private long count;
        LimitedInputStream(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read(); if (value != -1 && ++count > limit) throw new IOException("length mismatch"); return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, (int)Math.min(len, limit - count + 1));
            if (n > 0 && (count += n) > limit) throw new IOException("length mismatch"); return n;
        }
    }
}
