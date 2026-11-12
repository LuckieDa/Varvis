package org.nbscn.varvis.variantanalysis.persistence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AcmgStoredJsonTest {
    @Test void originalSmallAndLegacyJsonUnchanged() {
        assertNull(AcmgStoredJson.decode(null));
        assertEquals("{\"a\":1}",AcmgStoredJson.encode("{\"a\":1}"));
        assertEquals("{\"a\":1}",AcmgStoredJson.decode("{\"a\":1}"));
    }
    @Test void largeUnicodeRoundTripIsByteExact() {
        String raw="{\"text\":\""+"完整来源记录ABC".repeat(150000)+"\"}";
        String stored=AcmgStoredJson.encode(raw);
        assertTrue(stored.length()<raw.length()/10);
        assertEquals(raw,AcmgStoredJson.decode(stored));
    }
    @Test void corruptedDigestAndLengthRejected() {
        String stored=AcmgStoredJson.encode("x".repeat(300000));
        assertThrows(IllegalStateException.class,()->AcmgStoredJson.decode(stored.replace(":300000:",":299999:")));
        String[] parts=stored.split(":",4);parts[2]="0".repeat(64);
        assertThrows(IllegalStateException.class,()->AcmgStoredJson.decode(String.join(":",parts)));
        assertThrows(IllegalStateException.class,()->AcmgStoredJson.decode("VARVIS_GZIP_JSON_V1:2147483647:"+"0".repeat(64)+":AA=="));
    }
    @Test void utf8AcrossWriterBoundariesAndOldEnvelopesStayExact() throws Exception {
        String raw = " ".repeat(8191) + "😀病例原文\r\n".repeat(40000);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            gzip.write(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String legacy = "VARVIS_GZIP_JSON_V1:" + raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + ":" + AcmgBusinessStore.sha(raw) + ":" + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        assertEquals(raw, AcmgStoredJson.decode(legacy));
        assertEquals(raw, AcmgStoredJson.decode(AcmgStoredJson.encode(raw)));
        String[] parts = legacy.split(":", 4);
        parts[1] = "1"; // A tiny declared size must stop a large decompressed payload.
        assertThrows(IllegalStateException.class, () -> AcmgStoredJson.decode(String.join(":", parts)));
        byte[] corrupted = bytes.toByteArray(); corrupted[corrupted.length - 8] ^= 1; // CRC
        assertThrows(IllegalStateException.class, () -> AcmgStoredJson.decode(legacy.substring(0, legacy.lastIndexOf(':') + 1)
                + java.util.Base64.getEncoder().encodeToString(corrupted)));
    }
}
