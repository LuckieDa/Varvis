package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded source work only. Durable results never mutate an ACMG run or invoke a calculator. */
public final class DeNovoSourceTasks implements AutoCloseable {
    public static final String VERSION = "denovo-source-task-v1";
    public static final String RETRY_VERSION = "denovo-source-explicit-retry-v1";
    public static final int MAX_ATTEMPTS = 3;
    static final int MAX_IDENTITY = 2 * 1024 * 1024;
    private static final int MAX_RESULT = 32 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final class Shared {
        static final DeNovoSourceTasks INSTANCE = create();
        static DeNovoSourceTasks create() {
            var tasks = new DeNovoSourceTasks(1, 4);
            Runtime.getRuntime().addShutdownHook(new Thread(tasks::close, "denovo-source-shutdown"));
            return tasks;
        }
    }
    public static DeNovoSourceTasks shared() { return Shared.INSTANCE; }
    static final class SourceFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        SourceFailure(String reason) { super(reason); this.reason = reason.matches("[A-Z][A-Z0-9_]{0,160}") ? reason : "DENOVO_TASK_EXECUTION_FAILED"; }
    }
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final long budgetMillis;
    private final boolean modelWork;
    private final Map<Path, Work> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    DeNovoSourceTasks(int concurrency, int queued) {
        this(concurrency, queued, 135000);
    }
    DeNovoSourceTasks(int concurrency, int queued, long budgetMillis) {
        this(concurrency, queued, budgetMillis, false);
    }
    DeNovoSourceTasks(int concurrency, int queued, long budgetMillis, boolean modelWork) {
        this.modelWork = modelWork;
        this.budgetMillis = budgetMillis;
        workers = new ThreadPoolExecutor(concurrency, concurrency, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queued), task -> new Thread(task, "denovo-source-worker"));
        workers.allowCoreThreadTimeOut(true);
        timer = new ScheduledThreadPoolExecutor(1, task -> { var t = new Thread(task,"denovo-source-deadline"); t.setDaemon(true); return t; });
        timer.setRemoveOnCancelPolicy(true);
    }
    @FunctionalInterface interface Action { byte[] run(Duration remaining) throws Exception; }
    public record Read(ObjectNode status, byte[] result) {
        public Read { status = status.deepCopy(); result = result == null ? null : result.clone(); }
        @Override public ObjectNode status() { return status.deepCopy(); }
        @Override public byte[] result() { return result == null ? null : result.clone(); }
    }

    /** The first caller claims an OS lock; repeated calls only read, including after a JVM restart. */
    synchronized Read start(Path root, byte[] identity, Action action) throws IOException {
        if (identity.length == 0 || identity.length > MAX_IDENTITY) throw new IOException("DENOVO_TASK_IDENTITY_LIMIT");
        String id = FamilyDeNovoJson.sha256(identity); Path directory = directory(root, id);
        if (Files.exists(directory)) return read(root, id);
        if (closed.get()) return failed(id, "DENOVO_TASK_EXECUTOR_CLOSED");
        Files.createDirectories(directory.getParent());
        try { Files.createDirectory(directory); }
        catch (FileAlreadyExistsException race) { return read(root, id); }
        FileChannel channel = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("DENOVO_TASK_OWNERSHIP_UNAVAILABLE");
            Files.write(directory.resolve("identity.json"), identity, StandardOpenOption.CREATE_NEW);
            var status = state(id, "QUEUED").put("createdAt", Instant.now().toString()).put("budgetMillisIncludingQueue",budgetMillis);
            writeState(directory, status);
            var work = new Work(directory, channel, lock, status, action);
            active.put(directory, work);
            try {
                work.alarm = timer.schedule(work::expire, budgetMillis, TimeUnit.MILLISECONDS);
                workers.execute(work);
            }
            catch (RejectedExecutionException full) { work.reject("DENOVO_TASK_QUEUE_FULL"); }
            // Return a frozen receipt. The worker must not mutate this caller's pending result.
            return read(root, id);
        } catch (IOException | RuntimeException error) {
            if (lock != null && lock.isValid()) lock.release();
            channel.close(); throw error;
        }
    }

    /** No creation, retry, worker launch or scientific execution is permitted here. */
    public Read read(Path root, String id) throws IOException {
        Read read = readStored(root,id); var status=read.status();
        int attempt=1;
        Path identityFile=directory(root,id).resolve("identity.json");
        if (Files.isRegularFile(identityFile)) {
            byte[] bytes=bounded(identityFile,MAX_IDENTITY);
            if (id.equals(FamilyDeNovoJson.sha256(bytes))) try {
                var identity=JSON.readTree(bytes);
                if (RETRY_VERSION.equals(identity.path("retryVersion").asText()))
                    attempt=identity.path("attemptNumber").asInt(MAX_ATTEMPTS);
            } catch (IOException invalidLegacyIdentity) { /* Plain legacy identities have no retry generation. */ }
        }
        boolean recoverable=List.of("FAILED","CANCELLED").contains(status.path("status").asText())
                && java.util.Set.of("DENOVO_TASK_QUEUE_FULL","DENOVO_TASK_TIMEOUT","DENOVO_TASK_QUEUE_TIMEOUT",
                    "DENOVO_TASK_CANCELLED","DENOVO_TASK_INTERRUPTED","DENOVO_SUPPLY_PROCESS_TIMEOUT",
                    "DENOVO_SUPPLY_PROCESS_FAILED").contains(status.path("reasonCode").asText());
        boolean ownerHeld=recoverable && owned(directory(root,id));
        status.put("attemptNumber",attempt).put("maximumAttempts",MAX_ATTEMPTS)
                .put("canRetry",recoverable && !modelWork && !ownerHeld && attempt<MAX_ATTEMPTS);
        if (recoverable && attempt>=MAX_ATTEMPTS) status.put("retryBlockedReason","DENOVO_TASK_RETRY_LIMIT");
        else if(ownerHeld) status.put("retryBlockedReason","DENOVO_TASK_EXIT_PENDING");
        else if("FAILED".equals(status.path("status").asText()) && !recoverable)
            status.put("retryBlockedReason","DENOVO_TASK_RETRY_NOT_SAFE");
        return new Read(status,read.result());
    }

    /** Interrupt only this executor's owned work; never delete originals or retry it. */
    public Read cancel(Path root, String id) throws IOException {
        Read before=read(root,id);
        Work work=active.get(directory(root,id));
        if(work!=null) {
            work.cancelRequested=true;
            if(workers.remove(work)) work.reject("DENOVO_TASK_CANCELLED");
            else {Thread owner=work.owner;if(owner!=null && !work.released.get()) owner.interrupt();}
        }
        return work==null?before:read(root,id);
    }

    /** An explicit business retry may append one deterministic attempt; ordinary requests only follow it. */
    synchronized Read prepare(Path root, byte[] identity, Action action, String retryOf) throws IOException {
        String base=FamilyDeNovoJson.sha256(identity);
        if (retryOf!=null && (!retryOf.matches("[a-f0-9]{64}") || !Files.isDirectory(directory(root,base))))
            return failed(base,"DENOVO_TASK_RETRY_IDENTITY_MISMATCH");
        Read current=start(root,identity,action); String previous=base; int attempt=1;
        for (int next=2;next<=MAX_ATTEMPTS;next++) {
            byte[] nextIdentity=retryIdentity(base,previous,next);String nextId=FamilyDeNovoJson.sha256(nextIdentity);
            if(!Files.exists(directory(root,nextId))) break;
            current=read(root,nextId);previous=nextId;attempt=next;
        }
        if (retryOf==null || !retryOf.equals(previous) || !current.status().path("canRetry").asBoolean()) return current;
        return start(root,retryIdentity(base,previous,attempt+1),action);
    }
    private static byte[] retryIdentity(String base, String previous, int attempt) throws IOException {
        return JSON.writeValueAsBytes(JSON.createObjectNode().put("retryVersion",RETRY_VERSION)
                .put("rootTaskId",base).put("retryOfTaskId",previous).put("attemptNumber",attempt));
    }

    private Read readStored(Path root, String id) throws IOException {
        Path directory = directory(root, id);
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) return failed(id, "DENOVO_TASK_NOT_FOUND");
        if (!Files.isRegularFile(directory.resolve("identity.json"))) return failed(id, "DENOVO_TASK_INCOMPLETE");
        if (!id.equals(FamilyDeNovoJson.sha256(bounded(directory.resolve("identity.json"), MAX_IDENTITY))))
            return failed(id, "DENOVO_TASK_IDENTITY_MISMATCH");
        Path stateFile = directory.resolve("state.terminal.json");
        if (!Files.exists(stateFile)) stateFile=directory.resolve("state.running.json");
        if (!Files.exists(stateFile)) stateFile=directory.resolve("state.queued.json");
        ObjectNode status;
        try { status = (ObjectNode) JSON.readTree(bounded(stateFile, 65536)); }
        catch (RuntimeException invalid) { return failed(id, "DENOVO_TASK_RECEIPT_INVALID"); }
        if (!(modelWork ? "denovo-public-literature-task-v1" : VERSION).equals(status.path("schemaVersion").asText()) || !id.equals(status.path("taskId").asText()))
            return failed(id, "DENOVO_TASK_RECEIPT_INVALID");
        if (!status.path("scientificExecution").isBoolean() || status.path("scientificExecution").asBoolean(true)
                || (modelWork ? !status.path("modelCalls").isNull() : !status.path("modelCalls").isIntegralNumber() || status.path("modelCalls").asInt(-1)!=0)
                || status.has("reasonCode") && !status.path("reasonCode").asText().matches("[A-Z][A-Z0-9_]{0,160}"))
            return failed(id,"DENOVO_TASK_RECEIPT_INVALID");
        status.retain("schemaVersion","taskId","status","scientificExecution","modelCalls","createdAt","startedAt",
                "finishedAt","budgetMillisIncludingQueue","resultSha256","reasonCode");
        String phase = status.path("status").asText();
        if (!List.of("QUEUED", "RUNNING", "READY", "FAILED", "CANCELLED").contains(phase))
            return failed(id, "DENOVO_TASK_RECEIPT_INVALID");
        if (List.of("QUEUED", "RUNNING").contains(phase) && !owned(directory)) {
            // Completion can publish its terminal receipt after this reader selected RUNNING.
            if (Files.isRegularFile(directory.resolve("state.terminal.json"))) return read(root,id);
            return failed(id, "DENOVO_TASK_INTERRUPTED");
        }
        if (!"READY".equals(phase)) return new Read(status, null);
        byte[] result = bounded(directory.resolve("result.json"), MAX_RESULT);
        if (!FamilyDeNovoJson.sha256(result).equals(status.path("resultSha256").asText()))
            return failed(id, "DENOVO_TASK_RESULT_MISMATCH");
        return new Read(status, result);
    }

    private static boolean owned(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) { return lock == null; }
            catch (OverlappingFileLockException liveInThisJvm) { return true; }
        }
    }
    private static Path directory(Path root, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IOException("DENOVO_TASK_ID_INVALID");
        return root.toAbsolutePath().normalize().resolve(id);
    }
    private ObjectNode state(String id, String status) {
        if (modelWork) return JSON.createObjectNode().put("schemaVersion", "denovo-public-literature-task-v1").put("taskId", id).put("status", status).put("scientificExecution", false).putNull("modelCalls");
        return JSON.createObjectNode().put("schemaVersion", VERSION).put("taskId", id).put("status", status)
                .put("scientificExecution", false).put("modelCalls", 0);
    }
    private Read failed(String id, String reason) { return new Read(state(id, "FAILED").put("reasonCode", reason), null); }
    private static byte[] bounded(Path file, int max) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("DENOVO_TASK_LINK_FORBIDDEN");
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(max + 1);
            if (bytes.length == 0 || bytes.length > max) throw new IOException("DENOVO_TASK_FILE_LIMIT");
            return bytes;
        }
    }
    private static void writeState(Path directory, ObjectNode state) throws IOException {
        Path next = Files.createTempFile(directory, "receipt-", ".json");
        Files.write(next, JSON.writeValueAsBytes(state));
        String phase=state.path("status").asText();
        String name="QUEUED".equals(phase)?"queued":"RUNNING".equals(phase)?"running":"terminal";
        // Publish each phase once. Never replace a receipt while another Windows reader holds it open.
        Path destination=directory.resolve("state."+name+".json");
        if (Files.exists(destination)) throw new IOException("DENOVO_TASK_STATE_ALREADY_PUBLISHED");
        Files.move(next, destination, StandardCopyOption.ATOMIC_MOVE);
    }
    private final class Work implements Runnable {
        final Path directory;
        final FileChannel channel;
        final FileLock lock;
        final ObjectNode status;
        final Action action;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        volatile Thread owner;
        volatile ScheduledFuture<?> alarm;
        volatile boolean expired;
        volatile boolean cancelRequested;
        final AtomicBoolean released = new AtomicBoolean();
        Work(Path directory, FileChannel channel, FileLock lock, ObjectNode status, Action action) {
            this.directory = directory; this.channel = channel; this.lock = lock; this.status = status; this.action = action;
        }
        @Override public void run() {
            owner = Thread.currentThread();
            try {
                writeState(directory, status.put("status", "RUNNING").put("startedAt", Instant.now().toString()));
                if (System.nanoTime()>=deadline) expired=true;
                if (Thread.currentThread().isInterrupted() || expired || cancelRequested) throw new InterruptedException();
                byte[] result = action.run(Duration.ofNanos(Math.max(1,deadline-System.nanoTime())));
                if (Thread.currentThread().isInterrupted() || cancelRequested) throw new InterruptedException();
                if (result == null || result.length == 0 || result.length > MAX_RESULT) throw new IOException("DENOVO_TASK_RESULT_LIMIT");
                Files.write(directory.resolve("result.json"), result, StandardOpenOption.CREATE_NEW);
                status.put("status", "READY").put("resultSha256", FamilyDeNovoJson.sha256(result));
            } catch (InterruptedException interrupted) {
                status.put("status", "CANCELLED").put("reasonCode", "DENOVO_TASK_CANCELLED");
                Thread.currentThread().interrupt();
            } catch (SourceFailure failure) {
                status.put("status", "FAILED").put("reasonCode", failure.reason);
            } catch (Exception failure) {
                status.put("status", "FAILED").put("reasonCode", "DENOVO_TASK_EXECUTION_FAILED");
                try {
                    Files.writeString(directory.resolve("failure.txt"),failure.getClass().getName()+": "+failure.getMessage(),StandardOpenOption.CREATE_NEW);
                } catch (IOException unavailable) { /* Terminal status still records the failure. */ }
            } finally {
                if (System.nanoTime()>=deadline) expired=true;
                if (expired) status.put("status","FAILED").put("reasonCode","DENOVO_TASK_TIMEOUT").remove("resultSha256");
                else if (Thread.currentThread().isInterrupted() || cancelRequested) status.put("status","CANCELLED").put("reasonCode","DENOVO_TASK_CANCELLED").remove("resultSha256");
                // A torn/failed receipt is reported as interrupted by the next read, never as READY.
                try { writeState(directory, status.put("finishedAt", Instant.now().toString())); }
                catch (IOException unavailable) { /* Source is retained; the status reader fails closed. */ }
                release();
            }
        }
        void expire() {
            expired = true;
            if (workers.remove(this)) reject("DENOVO_TASK_QUEUE_TIMEOUT");
            else { Thread thread = owner; if (thread != null && !released.get()) thread.interrupt(); }
        }
        void reject(String reason) {
            try { writeState(directory, status.put("status", "DENOVO_TASK_CANCELLED".equals(reason)?"CANCELLED":"FAILED").put("reasonCode", reason).put("finishedAt", Instant.now().toString())); }
            catch (IOException unavailable) { /* No READY result can be produced. */ }
            finally { release(); }
        }
        void release() {
            if (!released.compareAndSet(false, true)) return;
            if (alarm != null) alarm.cancel(false);
            active.remove(directory, this);
            try { lock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timer.shutdownNow();
        for (var queued : workers.shutdownNow()) ((Work) queued).reject("DENOVO_TASK_CANCELLED");
        try { workers.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
