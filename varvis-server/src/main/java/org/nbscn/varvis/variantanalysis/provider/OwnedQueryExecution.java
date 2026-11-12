package org.nbscn.varvis.variantanalysis.provider;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Bounded caller, fixed owner capacity. Only the owner ever touches native handles. */
final class OwnedQueryExecution {
    static final String VERSION = "windows-job-owner-v1";
    static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
    static final OwnerPool WINDOWS = new OwnerPool(4, WindowsJobExecution::new);

    record CommandSpec(List<String> argv, Path cwd, Map<String, String> envDelta, Path output, Path error,
                       String identity, Map<Path, Long> outputLimits, boolean rejectOrphanedRoot,
                       boolean preserveDiagnosticOutput, long descendantExitGraceMillis) {
        CommandSpec {
            argv = List.copyOf(argv); envDelta = Map.copyOf(envDelta); outputLimits = Map.copyOf(outputLimits);
            if (descendantExitGraceMillis < 0 || descendantExitGraceMillis > 500
                    || (descendantExitGraceMillis > 0 && !rejectOrphanedRoot))
                throw new IllegalArgumentException("INVALID_DESCENDANT_EXIT_GRACE");
        }
        CommandSpec(List<String> argv, Path cwd, Map<String, String> envDelta, Path output, Path error,
                    String identity, Map<Path, Long> outputLimits, boolean rejectOrphanedRoot,
                    boolean preserveDiagnosticOutput) {
            this(argv, cwd, envDelta, output, error, identity, outputLimits, rejectOrphanedRoot, preserveDiagnosticOutput, 0);
        }
        CommandSpec(List<String> argv, Path cwd, Map<String, String> envDelta, Path output, Path error, String identity) {
            this(argv, cwd, envDelta, output, error, identity, Map.of(), false, false);
        }
    }
    interface NativeExecution {
        void launch(CommandSpec command) throws IOException;
        void resume() throws IOException;
        boolean rootExited(int waitMillis) throws IOException;
        int exitCode() throws IOException;
        int activeProcesses() throws IOException;
        void terminate() throws IOException;
        void close() throws IOException;
        default Map<String, Object> identity() { return Map.of(); }
        default List<Map<String, Object>> memberIdentities() throws IOException { return List.of(); }
        default Map<String, Object> diagnostics(String stage) throws IOException { return Map.of(); }
        default void releaseExitedRootReferences() throws IOException { }
    }
    record Cancellation(String trigger, long requestedAt) { }
    record Outcome(int exitCode, String failure) { }

    static final class Ticket {
        final String id = UUID.randomUUID().toString();
        final AtomicReference<Cancellation> cancellation = new AtomicReference<>();
        final CompletableFuture<Outcome> done = new CompletableFuture<>();
        final AtomicReference<String> state = new AtomicReference<>("RESERVED");
        final CommandSpec command;
        final long deadline;
        volatile boolean launchSettled, jobBound, terminationRequested, exitConfirmed, rootExitObserved;
        volatile int active = -1;
        volatile int activeAtRootExit = -1;
        volatile Integer rootExitCodeObserved;
        volatile String stage = "RESERVED", cleanupFailure = "";
        volatile long launchMillis, cleanupCompletedAt, naturalCleanupStartedAt;
        volatile long naturalCleanupMillis;
        volatile Map<String, Object> rootIdentity = Map.of();
        volatile List<Map<String, Object>> members = List.of();
        volatile List<Map<String, Object>> lifecycle = List.of();
        void snapshot(NativeExecution execution, String phase) throws IOException {
            if (!command.rejectOrphanedRoot()) return;
            var snapshots = new ArrayList<>(lifecycle); snapshots.add(execution.diagnostics(phase));
            lifecycle = List.copyOf(snapshots);
        }
        void cleanupSnapshot(NativeExecution execution, String phase) {
            try { snapshot(execution, phase); }
            catch (IOException diagnosticFailure) {
                var snapshots = new ArrayList<>(lifecycle);
                snapshots.add(Map.of("stage", phase, "diagnosticFailure", Objects.toString(diagnosticFailure.getMessage(), "UNKNOWN")));
                lifecycle = List.copyOf(snapshots);
            }
        }
        Ticket(CommandSpec command, long budgetMillis) {
            this.command = command; deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        }
        void cancel(String trigger) {
            cancellation.compareAndSet(null, new Cancellation(trigger, System.nanoTime()));
            state.getAndUpdate(value -> "RELEASED".equals(value) ? value : "CANCELLING");
        }
        Map<String, Object> receipt() {
            var result = new LinkedHashMap<String, Object>();
            result.put("ownershipVersion", VERSION); result.put("attemptId", id);
            result.put("topology", "WINDOWS_LOCAL_PROCESS_TREE"); result.put("ownershipStrategy", "CREATE_PROCESS_JOB_LIST");
            result.put("launchSettled", launchSettled); result.put("jobBound", jobBound);
            result.put("rootExitObserved", rootExitObserved);
            result.put("rootExitCodeObserved", rootExitCodeObserved == null ? "UNKNOWN" : rootExitCodeObserved);
            result.put("activeAtRootExit", activeAtRootExit < 0 ? "UNKNOWN" : activeAtRootExit);
            result.put("ownedProcessesObserved", members);
            result.put("lifecycle", lifecycle);
            result.putAll(rootIdentity); result.put("launchMillis", launchMillis);
            var cancel = cancellation.get();
            long completed = cleanupCompletedAt == 0 ? System.nanoTime() : cleanupCompletedAt;
            boolean confirmed = launchSettled && exitConfirmed && done.isDone();
            long cleanupStart = cleanupStartedAt();
            boolean within = cleanupStart == 0 || completed - cleanupStart <= CLEANUP_NANOS;
            result.put("cleanupTrigger", cancel == null ? "NORMAL_EXIT" : cancel.trigger());
            result.put("terminationRequested", terminationRequested);
            result.put("ownedProcessesStopped", confirmed); result.put("exitConfirmed", confirmed);
            result.put("activeProcessCount", active < 0 ? "UNKNOWN" : active);
            result.put("cleanupBudgetMillis", 2000L);
            result.put("cleanupElapsedMillis", cleanupStart == 0 ? 0L : TimeUnit.NANOSECONDS.toMillis(completed - cleanupStart));
            result.put("naturalCleanupMillis", naturalCleanupMillis);
            if (command.descendantExitGraceMillis() > 0)
                result.put("descendantExitGraceMillis", command.descendantExitGraceMillis());
            result.put("cleanupWithinBudget", within);
            result.put("cleanupOutcome", confirmed && within ? "STOPPED" : "UNCONFIRMED");
            result.put("cleanupReason", confirmed && within ? "JOB_EMPTY_HANDLES_CLOSED"
                    : cleanupFailure.isEmpty() ? "CLEANUP_DEADLINE_EXCEEDED" : cleanupFailure);
            result.put("cleanupStage", stage); result.put("slotDisposition", done.isDone() ? "RELEASED" : "RETAINED");
            result.put("retryAllowed", confirmed && within && (cancel == null || !"INTERRUPTED".equals(cancel.trigger())));
            return result;
        }
        long cleanupStartedAt() {
            var cancel = cancellation.get();
            return naturalCleanupStartedAt == 0 ? cancel == null ? 0 : cancel.requestedAt()
                    : cancel == null ? naturalCleanupStartedAt : Math.min(naturalCleanupStartedAt, cancel.requestedAt());
        }
    }

    static final class OwnerPool {
        private final int capacity;
        private final Supplier<NativeExecution> factory;
        private final ConcurrentMap<String, Ticket> active = new ConcurrentHashMap<>();
        private final Deque<Map<String, Object>> completedCleanup = new ArrayDeque<>();
        private final ThreadPoolExecutor workers;
        OwnerPool(int capacity, Supplier<NativeExecution> factory) {
            this.capacity = capacity; this.factory = factory;
            workers = new ThreadPoolExecutor(capacity, capacity, 5, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
                Thread thread = new Thread(task, "gnomad-owned-execution");
                thread.setDaemon(false); return thread;
            });
            workers.allowCoreThreadTimeOut(true);
        }
        synchronized Ticket launch(CommandSpec command, long budgetMillis, int concurrency) throws IOException {
            if (active.size() >= Math.max(1, Math.min(capacity, concurrency)))
                throw new IOException("OWNED_EXECUTOR_DEGRADED_NO_SLOT");
            Ticket ticket = new Ticket(command, budgetMillis); active.put(ticket.id, ticket);
            try { workers.execute(() -> own(ticket)); }
            catch (RejectedExecutionException failure) { active.remove(ticket.id); throw new IOException("OWNED_EXECUTOR_NO_SLOT", failure); }
            return ticket;
        }
        int occupiedSlots() { return active.size(); }
        List<Map<String, Object>> outstanding() { return active.values().stream().map(value -> Objects.requireNonNull(value).receipt()).toList(); }
        synchronized List<Map<String, Object>> cleanupHistory() { return List.copyOf(completedCleanup); }
        private synchronized void rememberCleanup(Ticket ticket) {
            if (completedCleanup.size() >= 2000) completedCleanup.removeFirst();
            completedCleanup.addLast(Collections.unmodifiableMap(ticket.receipt()));
        }
        void shutdown() { workers.shutdown(); } // Does not join a pending native operation.

        private void own(Ticket ticket) {
            NativeExecution execution = null;
            int exit = -1; String failure = "";
            var observed = new LinkedHashMap<String, Map<String, Object>>();
            long launchStarted = System.nanoTime();
            try {
                ticket.stage = "CREATE_JOB_AND_SUSPENDED_PROCESS";
                execution = factory.get();
                if (ticket.cancellation.get() == null) {
                    execution.launch(ticket.command);
                    ticket.jobBound = true; ticket.rootIdentity = Map.copyOf(execution.identity());
                }
                ticket.launchSettled = true;
                ticket.launchMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - launchStarted);
                if (System.nanoTime() >= ticket.deadline) ticket.cancel("TIMEOUT");
                // Resume and cancellation compete through the ticket, never through a global native lock.
                if (ticket.cancellation.get() == null && ticket.state.compareAndSet("RESERVED", "RESUMING")) {
                    ticket.stage = "RESUMING"; execution.resume();
                    ticket.state.compareAndSet("RESUMING", "RUNNING");
                }
                while (ticket.cancellation.get() == null) {
                    if (System.nanoTime() >= ticket.deadline) { ticket.cancel("TIMEOUT"); break; }
                    for (var limit : ticket.command.outputLimits().entrySet()) {
                        if (Files.exists(limit.getKey()) && Files.size(limit.getKey()) > limit.getValue()) {
                            failure = "OUTPUT_LIMIT_EXCEEDED"; ticket.cancel("OUTPUT_LIMIT"); break;
                        }
                    }
                    if (ticket.cancellation.get() != null) break;
                    if (ticket.command.rejectOrphanedRoot()) {
                        for (var member : execution.memberIdentities()) {
                            observed.put(member.get("processId") + ":" + member.get("creationIdentity"), member);
                            if (observed.size() > 64) throw new IOException("OWNED_PROCESS_IDENTITY_LIMIT");
                        }
                        ticket.members = List.copyOf(observed.values());
                    }
                    ticket.stage = "WAITING_ROOT_AND_JOB";
                    if (execution.rootExited(20)) {
                        ticket.rootExitObserved = true;
                        ticket.rootExitCodeObserved = execution.exitCode();
                        ticket.active = execution.activeProcesses();
                        if (ticket.activeAtRootExit < 0) ticket.activeAtRootExit = ticket.active;
                        if (ticket.command.rejectOrphanedRoot()) {
                            ticket.naturalCleanupStartedAt = System.nanoTime();
                            ticket.snapshot(execution, "ROOT_SIGNALED_BEFORE_REFERENCE_RELEASE");
                            execution.releaseExitedRootReferences();
                            ticket.snapshot(execution, "AFTER_EXITED_ROOT_REFERENCES_RELEASED");
                            ticket.active = execution.activeProcesses();
                            while (ticket.active != 0 && ticket.cancellation.get() == null
                                    && System.nanoTime() - ticket.naturalCleanupStartedAt < TimeUnit.MILLISECONDS.toNanos(500)) {
                                var state = execution.diagnostics("NATURAL_COMPLETION_CHECK");
                                // Opt-in source workers may observe short-lived console teardown. Success still
                                // requires an empty Job; a live child is never accepted or exempted by name.
                                long grace = TimeUnit.MILLISECONDS.toNanos(ticket.command.descendantExitGraceMillis());
                                if (!onlyExitedMembers(state) && System.nanoTime() - ticket.naturalCleanupStartedAt >= grace) break;
                                if (grace > 0 && System.nanoTime() >= ticket.deadline) { ticket.cancel("TIMEOUT"); break; }
                                long remaining = ticket.naturalCleanupStartedAt + TimeUnit.MILLISECONDS.toNanos(500) - System.nanoTime();
                                if (remaining <= 0) break;
                                try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20))); }
                                catch (InterruptedException ignored) { /* only ticket cancellation controls this owner */ }
                                ticket.active = execution.activeProcesses();
                            }
                            ticket.naturalCleanupMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ticket.naturalCleanupStartedAt);
                            ticket.snapshot(execution, "AFTER_BOUNDED_NATURAL_COMPLETION");
                            // The final accounting poll may itself cross the execution deadline while
                            // observing Job zero. Opt-in grace cannot turn that late observation into success.
                            if (ticket.command.descendantExitGraceMillis() > 0) {
                                if (System.nanoTime() >= ticket.deadline) ticket.cancel("TIMEOUT");
                                if (ticket.cancellation.get() != null) break;
                            }
                        }
                        if (ticket.active == 0) { exit = execution.exitCode(); break; }
                        if (ticket.command.rejectOrphanedRoot()) {
                            failure = "ROOT_EXITED_WITH_ACTIVE_DESCENDANTS";
                            ticket.cancel("ROOT_EXITED_WITH_ACTIVE_DESCENDANTS"); break;
                        }
                        pause();
                    }
                }
            } catch (Exception | LinkageError invalid) {
                ticket.launchSettled = true;
                failure = invalid instanceof IOException ? Objects.toString(invalid.getMessage(), "OWNED_EXECUTION_FAILED")
                        : "OWNED_EXECUTION_FAILED_" + invalid.getClass().getSimpleName();
                ticket.cancel("EXECUTION_FAILED");
            }
            // A native call that has not returned still occupies this same owner/slot. No replacement worker.
            if (execution != null) {
                boolean terminated = false;
                for (;;) {
                    try {
                        if ((ticket.cancellation.get() != null || !failure.isEmpty()) && !terminated) {
                            ticket.cleanupSnapshot(execution, "BEFORE_TERMINATE_JOB");
                            ticket.stage = "TERMINATING_JOB"; ticket.terminationRequested = true;
                            execution.terminate(); terminated = true;
                        }
                        if (ticket.jobBound && ticket.command.rejectOrphanedRoot() && execution.rootExited(0)) {
                            ticket.rootExitObserved = true;
                            if (ticket.rootExitCodeObserved == null) ticket.rootExitCodeObserved = execution.exitCode();
                            execution.releaseExitedRootReferences();
                        }
                        ticket.stage = "CONFIRMING_JOB_EMPTY"; ticket.active = execution.activeProcesses();
                        if (ticket.active != 0) { pause(); continue; }
                        if (ticket.jobBound && ticket.command.rejectOrphanedRoot()) {
                            ticket.rootExitObserved = execution.rootExited(20);
                            if (!ticket.rootExitObserved) { pause(); continue; }
                            if (ticket.rootExitCodeObserved == null) ticket.rootExitCodeObserved = execution.exitCode();
                            execution.releaseExitedRootReferences();
                        }
                        ticket.cleanupSnapshot(execution, "JOB_EMPTY_BEFORE_HANDLE_CLOSE");
                        ticket.stage = "CLOSING_HANDLES"; execution.close(); ticket.exitConfirmed = true;
                        break;
                    } catch (Exception closeFailure) {
                        ticket.cleanupFailure = "NATIVE_CLEANUP_FAILED_" + closeFailure.getClass().getSimpleName();
                        ticket.active = -1; pause();
                    }
                }
            } else ticket.exitConfirmed = true;
            // Cancelled output belongs to this ticket until the writer and all inherited handles are gone.
            if (ticket.cancellation.get() != null && !ticket.command.preserveDiagnosticOutput()) deleteOutput(ticket.command);
            ticket.cleanupCompletedAt = System.nanoTime(); ticket.stage = "CLOSED";
            ticket.state.set("RELEASED"); active.remove(ticket.id);
            ticket.done.complete(new Outcome(exit, failure));
            rememberCleanup(ticket); // Separate late-cleanup receipt; never mutates a published query result.
        }
    }

    static boolean onlyExitedMembers(Map<String, Object> diagnostic) {
        if (!(diagnostic.get("members") instanceof List<?> members)) return false;
        for (Object value : members) {
            if (!(value instanceof Map<?, ?> member) || !Boolean.TRUE.equals(member.get("signaled"))
                    || !Boolean.TRUE.equals(member.get("jobBound"))) return false;
        }
        // An empty PID list with lagging accounting is only observed within the same bounded window.
        return true;
    }

    static Outcome await(Ticket ticket) throws IOException, InterruptedException {
        boolean interrupted = false;
        try {
            try {
                long remaining = ticket.deadline - System.nanoTime();
                if (remaining > 0 && ticket.cancellation.get() == null) {
                    Outcome outcome;
                    if (ticket.command.rejectOrphanedRoot()) {
                        for (;;) {
                            long end = ticket.naturalCleanupStartedAt == 0 ? ticket.deadline
                                    : Math.min(ticket.deadline, ticket.naturalCleanupStartedAt + CLEANUP_NANOS);
                            remaining = end - System.nanoTime();
                            if (remaining <= 0) {
                                boolean sourceDeadline = ticket.command.descendantExitGraceMillis() > 0 && System.nanoTime() >= ticket.deadline;
                                ticket.cancel(ticket.naturalCleanupStartedAt == 0 || sourceDeadline ? "TIMEOUT" : "CLEANUP_UNCONFIRMED");
                                throw new TimeoutException();
                            }
                            try { outcome = ticket.done.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS); break; }
                            catch (TimeoutException polling) { if (ticket.cancellation.get() != null) throw polling; }
                        }
                    } else outcome = ticket.done.get(remaining, TimeUnit.NANOSECONDS);
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (ticket.cancellation.get() == null) return outcome;
                }
                if (ticket.cancellation.get() == null) ticket.cancel("TIMEOUT");
            } catch (TimeoutException timeout) { ticket.cancel("TIMEOUT"); }
            catch (InterruptedException cancellation) { interrupted = true; ticket.cancel("INTERRUPTED"); }
            var cancel = Objects.requireNonNull(ticket.cancellation.get());
            long deadline = ticket.cleanupStartedAt() + CLEANUP_NANOS;
            while (!ticket.done.isDone()) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) break;
                try { ticket.done.get(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException again) { interrupted = true; }
                catch (TimeoutException expired) { break; }
            }
            if (interrupted || "INTERRUPTED".equals(cancel.trigger())) throw new InterruptedException("Owned query interrupted");
            return ticket.done.getNow(new Outcome(-1, "CLEANUP_UNCONFIRMED"));
        } catch (ExecutionException impossible) { throw new IOException("OWNED_EXECUTION_FAILED", impossible); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void pause() {
        try { TimeUnit.MILLISECONDS.sleep(20); }
        catch (InterruptedException ignored) { /* owners are not interrupted; their ticket is authoritative */ }
    }
    static void deleteOutput(CommandSpec command) {
        for (Path path : List.of(command.output(), command.error())) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { /* retained private artifact */ }
        }
    }
}
