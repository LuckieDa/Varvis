package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionBatchFactCacheTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void exactKeyAndOwnedCopiesOnly() {
        var cache = new PredictionBatchFactCache(); var calls = new AtomicInteger();
        java.util.function.Supplier<com.fasterxml.jackson.databind.node.ObjectNode> read = () -> {
            calls.incrementAndGet(); return mapper.createObjectNode().put("raw", "fixed");
        };
        cache.obtain("pinned-allele-1", read, "FAILED").value().put("raw", "modified");
        var second = cache.obtain("pinned-allele-1", read, "FAILED");
        assertTrue(second.reused()); assertEquals("fixed", second.value().path("raw").asText());
        assertFalse(cache.obtain("different-resource-or-allele", read, "FAILED").reused());
        assertEquals(2, calls.get());
        assertFalse(new PredictionBatchFactCache().obtain("pinned-allele-1", read, "FAILED").reused());
    }
    @Test void failedOrNullAcquisitionCannotRepeatAndInterruptCannotUseCache() {
        var cache = new PredictionBatchFactCache(); var calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) assertEquals("FAILED", assertThrows(IllegalStateException.class,
                () -> cache.obtain("k", () -> { calls.incrementAndGet(); return null; }, "FAILED")).getMessage());
        assertEquals(1, calls.get());
        cache.obtain("valid", mapper::createObjectNode, "FAILED");
        Thread.currentThread().interrupt();
        try { assertEquals("PREDICTION_FACT_REUSE_INTERRUPTED", assertThrows(IllegalStateException.class,
                () -> cache.obtain("valid", mapper::createObjectNode, "FAILED")).getMessage()); }
        finally { Thread.interrupted(); }
    }
    @Test void boundedEntriesAndBytesNeverEvictIntoRepeatedReads() {
        var cache = new PredictionBatchFactCache();
        for (int i = 0; i < 100; i++) cache.obtain("k" + i, mapper::createObjectNode, "FAILED");
        assertEquals("PREDICTION_FACT_REUSE_ENTRY_LIMIT", assertThrows(IllegalStateException.class,
                () -> cache.obtain("overflow", () -> { fail("must not read"); return null; }, "FAILED")).getMessage());
        assertTrue(cache.obtain("k0", mapper::createObjectNode, "FAILED").reused());
        var bytes = new PredictionBatchFactCache(); var calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) assertEquals("PREDICTION_FACT_REUSE_BYTE_LIMIT", assertThrows(IllegalStateException.class,
                () -> bytes.obtain("large", () -> { calls.incrementAndGet(); return mapper.createObjectNode().put("raw", "x".repeat(16 * 1024 * 1024)); }, "FAILED")).getMessage());
        assertEquals(1, calls.get());
    }
}
