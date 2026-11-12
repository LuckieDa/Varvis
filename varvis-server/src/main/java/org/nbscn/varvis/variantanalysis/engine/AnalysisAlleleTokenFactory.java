package org.nbscn.varvis.variantanalysis.engine;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Creates analysis-scoped opaque tokens for source VCF alleles.
 *
 * <p>The secret never leaves this factory. Closing the factory clears its secret and prevents
 * further token generation, so callers can explicitly bound the token-linkability scope to one
 * analysis.</p>
 */
final class AnalysisAlleleTokenFactory implements AutoCloseable {

    private static final int SECRET_BYTES = 32;
    private static final int TOKEN_BYTES = 12;
    private static final byte[] DOMAIN =
            "varvis-analysis-allele-token-v1".getBytes(StandardCharsets.UTF_8);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] scopeSecret;
    private boolean closed;

    AnalysisAlleleTokenFactory() {
        this.scopeSecret = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(this.scopeSecret);
    }

    AnalysisAlleleTokenFactory(byte[] scopeSecret) {
        if (scopeSecret == null || scopeSecret.length != SECRET_BYTES) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_TOKEN_SCOPE_SECRET_INVALID");
        }
        this.scopeSecret = Arrays.copyOf(scopeSecret, scopeSecret.length);
    }

    synchronized String token(long sourceRecordOrdinal, int sourceAltIndex) {
        requireOpen();
        if (sourceRecordOrdinal <= 0L) {
            throw new IllegalArgumentException(
                    "ANALYSIS_ALLELE_TOKEN_SOURCE_RECORD_ORDINAL_INVALID");
        }
        if (sourceAltIndex <= 0) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_TOKEN_SOURCE_ALT_INDEX_INVALID");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(scopeSecret, "HmacSHA256"));
            mac.update(DOMAIN);
            mac.update(ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                    .putLong(sourceRecordOrdinal)
                    .putInt(sourceAltIndex)
                    .array());
            return truncatedHex(mac.doFinal());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "ANALYSIS_ALLELE_TOKEN_CRYPTO_UNAVAILABLE",
                    failure
            );
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(scopeSecret, (byte) 0);
            closed = true;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("ANALYSIS_ALLELE_TOKEN_FACTORY_CLOSED");
        }
    }

    private String truncatedHex(byte[] digest) {
        char[] value = new char[TOKEN_BYTES * 2];
        for (int index = 0; index < TOKEN_BYTES; index++) {
            int current = digest[index] & 0xff;
            value[index * 2] = HEX[current >>> 4];
            value[index * 2 + 1] = HEX[current & 0x0f];
        }
        return new String(value);
    }
}
