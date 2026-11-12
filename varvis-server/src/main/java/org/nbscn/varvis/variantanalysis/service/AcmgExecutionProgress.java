package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import org.springframework.lang.Nullable;

/** Ephemeral observation only. Never an execution receipt, result, or permission to retry. */
public final class AcmgExecutionProgress {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Session> RUNS = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public Session begin(String analysis, String operation) {
        if (operation == null || !operation.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("ACMG_PROGRESS_OPERATION_INVALID");
        sessions.entrySet().removeIf(e -> e.getValue().finished && e.getValue().updated < System.currentTimeMillis() - 86400000);
        if (sessions.size() >= 1000 || CURRENT.get() != null) throw new IllegalArgumentException("ACMG_PROGRESS_CAPACITY");
        var session = new Session(analysis, operation);
        if (sessions.putIfAbsent(operation, session) != null) throw new IllegalArgumentException("ACMG_PROGRESS_OPERATION_EXISTS");
        CURRENT.set(session);
        return session;
    }

    public ObjectNode read(String analysis, String operation) {
        var session = sessions.get(operation);
        if (session == null || !session.analysis.equals(analysis)) return JSON.createObjectNode()
                .put("status", "UNAVAILABLE").put("executionReceipt", false).put("analysisId", analysis).put("operationId", operation);
        return session.snapshot();
    }

    public static void stage(String phase, String candidate, String hypothesis, String criterion) {
        AcmgRunControl.checkpoint();
        var session = CURRENT.get();
        if (session != null) session.update(phase, candidate, hypothesis, criterion);
    }

    /** Observational only: failures propagate unchanged and measurements never enter result identity. */
    public static <T> T measure(String operation, Supplier<T> work) {
        long start = System.nanoTime();
        try { return work.get(); }
        finally {
            var session = CURRENT.get();
            if (session != null) session.record(operation, System.nanoTime() - start);
        }
    }

    public static void bindRun(String analysis, String runId) {
        var session = CURRENT.get();
        if (session != null && session.analysis.equals(analysis)) {
            synchronized (session) { session.runId = runId; }
            RUNS.put(runId, session);
        }
    }

    public static ObjectNode forRun(String analysis, String runId) {
        var session = RUNS.get(runId);
        return session != null && session.analysis.equals(analysis) ? session.snapshot()
                : JSON.createObjectNode().put("status", "UNAVAILABLE").put("executionReceipt", false);
    }

    /** Report the committed run even if the subsequent result read or response fails. */
    public static void committed(String analysis, String runId, String status) {
        var session = CURRENT.get();
        if (session != null && session.analysis.equals(analysis)) session.recordResult(status, runId);
    }

    public final class Session implements AutoCloseable {
        private final String analysis, operation;
        private final long started = System.currentTimeMillis();
        private volatile long updated = started;
        private volatile boolean finished;
        private String phase = "BACKGROUND_VERIFY", candidate, hypothesis, criterion, status = "RUNNING", runId;
        @Nullable private String reasonCode;
        private boolean returned;
        private long phaseStarted = System.nanoTime();
        private long finishedElapsed;
        private final LinkedHashMap<String, Long> phases = new LinkedHashMap<>();
        private final LinkedHashMap<String, long[]> operations = new LinkedHashMap<>();
        private Session(String analysis, String operation) { this.analysis = analysis; this.operation = operation; }
        private String phaseKey() { return phase + (criterion == null ? "" : ":" + criterion); }
        private void finishPhase(long now) { phases.merge(phaseKey(), Math.max(0, now - phaseStarted), (existingCount, addedCount) -> existingCount + addedCount); phaseStarted = now; }
        private synchronized void record(String name, long nanos) {
            if (!operations.containsKey(name) && operations.size() >= 64) return;
            var total = operations.computeIfAbsent(name, ignored -> new long[2]);
            total[0]++; total[1] += Math.max(0, nanos);
        }
        private synchronized void update(String next, String c, String h, String rule) {
            finishPhase(System.nanoTime());
            phase = next; candidate = c; hypothesis = h; criterion = rule; updated = System.currentTimeMillis();
        }
        public synchronized void complete(ObjectNode result) {
            recordResult(result.path("status").asText("UNKNOWN"), result.path("runId").asText(null));
        }
        private synchronized void recordResult(String resultStatus, @Nullable String resultRunId) {
            if (returned) return;
            returned = true;
            status = resultStatus;
            if (resultRunId != null) runId = resultRunId;
            switch (status) {
                case "RUNNING" -> update("EXISTING_RUN", null, null, null);
                case "COMPLETED", "COMPLETED_WITH_BLOCKERS" -> update("SAVED", null, null, null);
                case "CANCELLED" -> reasonCode = "ACMG_USER_STOPPED";
                default -> { }
            }
        }
        public synchronized void fail(@Nullable String reason) {
            if (returned) return;
            returned = true;
            status = "ACMG_USER_STOPPED".equals(reason) ? "CANCELLED" : "FAILED";
            reasonCode = reason != null && reason.matches("[A-Z][A-Z0-9_]{1,159}") ? reason : "ACMG_REQUEST_FAILED";
        }
        public synchronized ObjectNode snapshot() {
            var result = JSON.createObjectNode().put("schemaVersion", "acmg-live-progress-v1")
                    .put("analysisId", analysis).put("operationId", operation).put("status", status)
                    .put("phase", phase).put("candidateId", candidate).put("hypothesisId", hypothesis)
                    .put("criterion", criterion).put("runId", runId).put("executionReceipt", false)
                    .put("startedAt", Instant.ofEpochMilli(started).toString())
                    .put("updatedAt", Instant.ofEpochMilli(updated).toString())
                    .put("elapsedSeconds", finished ? finishedElapsed : Math.max(0, (System.currentTimeMillis() - started) / 1000));
            if (reasonCode != null) result.put("reasonCode", reasonCode);
            var totals = new LinkedHashMap<>(phases);
            if (!finished) totals.merge(phaseKey(), Math.max(0, System.nanoTime() - phaseStarted), (existingCount, addedCount) -> existingCount + addedCount);
            var timing = result.putObject("timing").put("semantics", "WALL_TIME_INCLUDES_IO_AND_PAUSES_OPERATIONS_OVERLAP_STAGES");
            var stages = timing.putArray("stages");
            totals.forEach((key, nanos) -> stages.addObject().put("stage", key).put("elapsedMillis", nanos / 1_000_000));
            var measured = timing.putArray("operations");
            operations.forEach((key, value) -> measured.addObject().put("operation", key).put("calls", value[0]).put("elapsedMillis", value[1] / 1_000_000));
            return result;
        }
        @Override public synchronized void close() {
            if (finished) return;
            finishPhase(System.nanoTime());
            finishedElapsed = Math.max(0, (System.currentTimeMillis() - started) / 1000);
            if (!returned) status = "STOPPED";
            if (runId != null) RUNS.remove(runId, this);
            finished = true; updated = System.currentTimeMillis(); CURRENT.remove();
        }
    }
}
