package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.lang.Nullable;

/** Request-owned raw captures only. No disease, transcript association, calibration or evaluated result. */
public final class PredictionBatchFactCache {
    public static final String VERSION = "1.0.0";
    private static final int MAX_ENTRIES = 100;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private final Map<String, Entry> entries = new HashMap<>();
    private long bytes;
    private record Entry(@Nullable ObjectNode value, @Nullable String failure) { }
    record Capture(ObjectNode value, boolean reused) { }

    synchronized Capture obtain(String exactKey, Supplier<ObjectNode> acquisition, String failureReason) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("PREDICTION_FACT_REUSE_INTERRUPTED");
        Entry entry = entries.get(exactKey);
        boolean reused = entry != null;
        if (entry == null) {
            if (entries.size() >= MAX_ENTRIES) throw new IllegalStateException("PREDICTION_FACT_REUSE_ENTRY_LIMIT");
            try {
                var value = Objects.requireNonNull(acquisition.get()).deepCopy();
                long size = value.toString().getBytes(StandardCharsets.UTF_8).length;
                if (size > MAX_BYTES - bytes) entry = new Entry(null, "PREDICTION_FACT_REUSE_BYTE_LIMIT");
                else { entry = new Entry(value, null); bytes += size; }
            } catch (RuntimeException failure) { entry = new Entry(null, failureReason); }
            entries.put(exactKey, entry); // Retain failure: another background is not permission to retry.
        }
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("PREDICTION_FACT_REUSE_INTERRUPTED");
        if (entry.failure != null) throw new IllegalStateException(entry.failure);
        return new Capture(Objects.requireNonNull(entry.value).deepCopy(), reused);
    }
}
