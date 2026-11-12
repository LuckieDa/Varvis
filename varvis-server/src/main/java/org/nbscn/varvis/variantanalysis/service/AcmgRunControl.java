package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cooperative control. A pause never holds a database transaction open. */
public final class AcmgRunControl {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Session> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final String HOST = System.getenv().getOrDefault("COMPUTERNAME", "unknown");
    private static final long PID = ProcessHandle.current().pid();
    private static final String START = ProcessHandle.current().info().startInstant().orElse(Instant.EPOCH).toString();
    private AcmgRunControl() { }
    public static Session begin(AcmgBusinessStore store, String analysis, String run) {
        var session = new Session(store, analysis, run);
        if (CURRENT.get() != null || ACTIVE.putIfAbsent(run, session) != null) throw new IllegalStateException("ACMG_CONTROL_ALREADY_ACTIVE");
        CURRENT.set(session);
        try { session.persist(); } catch (RuntimeException e) { session.close(); throw e; }
        return session;
    }
    public static void checkpoint() {
        var s = CURRENT.get(); if (s != null) s.check();
    }
    public static ObjectNode status(AcmgBusinessStore store, String analysis, String run) {
        var meta = store.metadata(analysis, run).orElseThrow(() -> new IllegalArgumentException("ACMG_RUN_NOT_FOUND"));
        var out = JSON.createObjectNode().put("analysisId", analysis).put("runId", run).put("state", meta.status());
        if (!"RUNNING".equals(meta.status())) return capabilities(out, false, false);
        var live = ACTIVE.get(run);
        if (live != null && live.analysis.equals(analysis)) return capabilities(live.snapshot(), true, false);
        out.put("state", "RECOVERY_REQUIRED");
        try {
            var owner = JSON.readTree(store.controlData(analysis, run));
            if (owner != null && "acmg-run-control-v1".equals(owner.path("schemaVersion").asText())
                    && !"unknown".equals(HOST) && HOST.equals(owner.path("host").asText())) {
                var process = ProcessHandle.of(owner.path("pid").asLong(-1));
                boolean sameProcess = process.isPresent() && process.get().isAlive()
                        && process.get().info().startInstant().map(mappedItem -> mappedItem.toString()).orElse("").equals(owner.path("processStart").asText());
                if (!sameProcess) out.put("state", "INTERRUPTED");
            }
        } catch (Exception unavailable) { /* No owner proof is not proof of a dead worker. */ }
        return capabilities(out, false, true);
    }
    public static ObjectNode command(AcmgBusinessStore store, String analysis, String run, String action) {
        var live = ACTIVE.get(run);
        if (live != null && live.analysis.equals(analysis)) {
            synchronized (live) {
                if ("PAUSE".equals(action) && "RUNNING".equals(live.state)) live.state = "PAUSE_REQUESTED";
                else if ("RESUME".equals(action) && ("PAUSED".equals(live.state) || "PAUSE_REQUESTED".equals(live.state))) live.state = "RUNNING";
                else if ("STOP".equals(action) && !"STOP_REQUESTED".equals(live.state)) live.state = "STOP_REQUESTED";
                else throw new IllegalArgumentException("ACMG_CONTROL_ACTION_NOT_AVAILABLE");
                live.notifyAll();
            }
            try { live.persist(); }
            catch (IllegalStateException changed) {
                // The worker can acknowledge STOP or commit before the control request saves its receipt.
                // Preserve that terminal result; never report a successful stop as a transport failure.
                if (!"ACMG_CONTROL_STATE_CHANGED".equals(changed.getMessage())
                        || store.metadata(analysis,run).map(r->"RUNNING".equals(r.status())).orElse(true)) throw changed;
            }
            return status(store,analysis,run);
        }
        var state = status(store, analysis, run).path("state").asText();
        if ("ACKNOWLEDGE_INTERRUPTED".equals(action) && ("INTERRUPTED".equals(state) || "RECOVERY_REQUIRED".equals(state))) {
            store.endControlled(run, "INTERRUPTED"); return status(store, analysis, run);
        }
        throw new IllegalArgumentException("ACMG_CONTROL_NO_ACTIVE_WORKER");
    }
    /** Live capabilities only; never persisted into an owner's receipt or a scientific result. */
    private static ObjectNode capabilities(ObjectNode out, boolean live, boolean acknowledge) {
        out.put("activeWorkerAvailable", live).put("savedScientificResultChanged", false);
        out.put("sameWorkerResumable", live && ("PAUSED".equals(out.path("state").asText())
                || "PAUSE_REQUESTED".equals(out.path("state").asText())));
        out.putArray("availableActions");
        if (live) {
            switch (out.path("state").asText()) {
                case "RUNNING" -> action(out, "PAUSE_RUN", "PAUSE", "暂停当前分析", false);
                case "PAUSE_REQUESTED", "PAUSED" -> action(out, "RESUME_RUN", "RESUME", "继续当前执行进程", false);
                default -> { }
            }
            if (!"STOP_REQUESTED".equals(out.path("state").asText()))
                action(out, "STOP_RUN", "STOP", "停止当前分析", true);
        } else if (acknowledge) {
            action(out, "ACKNOWLEDGE_INTERRUPTED", "ACKNOWLEDGE_INTERRUPTED", "确认旧运行已中断", true);
        }
        return out;
    }
    private static void action(ObjectNode out, String id, String command, String label, boolean confirmation) {
        var action = out.withArray("availableActions").addObject().put("actionId", id).put("controlAction", command)
                .put("label", label).put("available", true).put("requiresConfirmation", confirmation)
                .put("createsNewRun", false).put("createsNewSourceTask", false).put("overwritesSavedScientificResult", false);
        action.putObject("target").put("analysisId", out.path("analysisId").asText()).put("runId", out.path("runId").asText());
    }
    public static final class Stopped extends RuntimeException { private Stopped() { super("ACMG_USER_STOPPED"); } }
    public static final class Session implements AutoCloseable {
        private final AcmgBusinessStore store;
        private final String analysis, run;
        private String state = "RUNNING";
        private Session(AcmgBusinessStore store, String analysis, String run) { this.store=store;this.analysis=analysis;this.run=run; }
        private synchronized ObjectNode snapshot() { return JSON.createObjectNode().put("analysisId",analysis).put("runId",run).put("state",state); }
        private void persist() {
            var value = snapshot().put("schemaVersion","acmg-run-control-v1").put("host",HOST).put("pid",PID)
                    .put("processStart",START).put("updatedAt",Instant.now().toString());
            store.saveControl(analysis,run,value.toString());
        }
        private synchronized void check() {
            if ("STOP_REQUESTED".equals(state)) throw new Stopped();
            if (TransactionSynchronizationManager.isActualTransactionActive()) return;
            while ("PAUSE_REQUESTED".equals(state) || "PAUSED".equals(state)) {
                if (!"PAUSED".equals(state)) { state="PAUSED";persist(); }
                try { wait(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); state="STOP_REQUESTED"; }
            }
            if ("STOP_REQUESTED".equals(state)) throw new Stopped();
        }
        public void stopped() { store.endControlled(run,"CANCELLED"); }
        @Override public void close() { ACTIVE.remove(run,this);CURRENT.remove(); }
    }
}
