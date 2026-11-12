package org.nbscn.varvis.variantanalysis.lineage;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

import static org.nbscn.varvis.variantanalysis.lineage.LineageSpoolException.Code;

/**
 * Package-private, fixed-memory external sorter for private lineage validation facts.
 *
 * <p>Keys use unsigned bytewise ordering. Equal keys are ordered by their append sequence and are
 * never deduplicated; semantic duplicate handling belongs to the streaming lineage validator.
 * Immutable, checksummed runs are written only below a random owner-private session directory.
 * A session is never adopted after a process restart and this class never publishes a product
 * sidecar. Instances and their cursors have a single owner and are not thread-safe.</p>
 */
final class BoundedExternalSortSpool implements AutoCloseable {

    private static final byte[] RUN_MAGIC = new byte[] {
            'V', 'L', 'S', 'P', 'O', 'O', 'L', 1
    };
    private static final int FRAME_FIXED_BYTES = Integer.BYTES * 2 + Long.BYTES;
    private static final int BINDING_BYTES = 16;
    private static final int HEADER_BYTES = RUN_MAGIC.length + BINDING_BYTES * 2
            + Integer.BYTES + Long.BYTES * 2;
    private static final int FOOTER_BYTES = Integer.BYTES + BINDING_BYTES * 2
            + Integer.BYTES + Long.BYTES * 2 + 32;
    private static final int FOOTER_MARKER = -1;
    private static final int MAX_NAME_ATTEMPTS = 16;
    private static final String SESSION_PREFIX = ".analysis-allele-lineage-v1.spool.";
    private static final String RUN_PREFIX = "run-";
    private static final String RUN_SUFFIX = ".bin";
    private static final Pattern SESSION_NAME = Pattern.compile(
            "^\\.analysis-allele-lineage-v1\\.spool\\.[0-9a-f]{32}$");
    private static final Pattern RUN_NAME = Pattern.compile("^run-[0-9a-f]{32}\\.bin$");
    private static final Comparator<Entry> ENTRY_COMPARATOR = (left, right) -> {
        int key = Arrays.compareUnsigned(left.key, right.key);
        return key != 0 ? key : Long.compare(left.sequence, right.sequence);
    };

    private static final int HARD_MAX_ENTRY_BYTES = AnalysisAlleleLineageWire.MAX_LINE_BYTES;
    /*
     * A semantic pass can emit several bounded relation facts from one wire body row. Under the
     * v1 512 MiB / 2.5 million body-row envelope the proven fact ceiling is below 12.3 million;
     * sixteen million leaves a fixed implementation margin while remaining small enough for the
     * int-indexed sequence verifier. The wire body ceiling itself remains 2.5 million records.
     */
    private static final long HARD_MAX_RECORDS = 16_000_000L;
    private static final long HARD_MAX_BUFFERED_BYTES = 67_108_864L;
    private static final int HARD_MAX_BUFFERED_RECORDS = 65_536;
    private static final int HARD_MAX_MERGE_FAN_IN = 64;
    private static final long HARD_MAX_SPOOL_BYTES = 17_179_869_184L;
    private static final int HARD_MAX_LIVE_RUN_FILES = 256;
    private static final int HARD_MAX_CREATED_RUN_FILES = 4_096;

    private final Limits limits;
    private final SecureRandom random;
    private final FileIdentitySource identitySource;
    private final WorkspacePrivacyVerifier privacyVerifier;
    private final FaultHook faultHook;
    private final Observer observer;
    private final DirectorySnapshot workspace;
    private final SessionSnapshot session;
    private final List<Entry> buffer = new ArrayList<>();
    private final List<Run> runs = new ArrayList<>();
    private final Map<Path, OwnedFile> ownedFiles = new LinkedHashMap<>();

    private long bufferedBytes;
    private long appendedRecords;
    private long nextSequence;
    private long activeDiskBytes;
    private int createdRunFiles;
    private int openMergeReaders;
    private State state = State.APPENDING;
    private SortedCursor issuedCursor;
    private Code deferredCursorFailure;

    BoundedExternalSortSpool(Path privateWorkspace, Limits limits)
            throws LineageSpoolException {
        this(privateWorkspace, limits, new SecureRandom(), FileIdentitySource.STRICT,
                WorkspacePrivacyVerifier.POSIX_OWNER_ONLY, FaultHook.NOOP, Observer.NOOP);
    }

    BoundedExternalSortSpool(Path privateWorkspace,
                             Limits limits,
                             SecureRandom random,
                             FileIdentitySource identitySource,
                             WorkspacePrivacyVerifier privacyVerifier,
                             FaultHook faultHook,
                             Observer observer) throws LineageSpoolException {
        this.limits = validateLimits(limits);
        this.random = Objects.requireNonNull(random, "random");
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
        this.privacyVerifier = Objects.requireNonNull(privacyVerifier, "privacyVerifier");
        this.faultHook = Objects.requireNonNull(faultHook, "faultHook");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.workspace = validateWorkspace(privateWorkspace);
        this.session = createSession(this.workspace);
        try {
            observe();
        } catch (LineageSpoolException exception) {
            state = State.CLOSING;
            boolean clean = cleanupSession();
            state = State.CLOSED;
            if (!clean) {
                throw failure(Code.CLEANUP_INCOMPLETE);
            }
            throw exception;
        }
    }

    /** Adds one bounded binary fact. Input arrays are copied before the method returns. */
    void append(byte[] key, byte[] value) throws LineageSpoolException {
        requireState(State.APPENDING);
        try {
            appendChecked(key, value);
        } catch (LineageSpoolException exception) {
            state = State.FAILED;
            throw exception;
        } catch (RuntimeException exception) {
            state = State.FAILED;
            throw failure(Code.STATE_INVALID);
        }
    }

    private void appendChecked(byte[] key, byte[] value) throws LineageSpoolException {
        if (key == null || value == null || key.length == 0) {
            throw failure(Code.ENTRY_INVALID);
        }
        if (key.length > limits.maxKeyBytes() || value.length > limits.maxValueBytes()) {
            throw failure(Code.SPOOL_LIMIT_EXCEEDED);
        }
        if (appendedRecords >= limits.maxRecords() || nextSequence == Long.MAX_VALUE) {
            throw failure(Code.SPOOL_LIMIT_EXCEEDED);
        }
        long frameBytes = frameBytes(key.length, value.length);
        if (frameBytes > limits.maxBufferedBytes()) {
            throw failure(Code.SPOOL_LIMIT_EXCEEDED);
        }
        if (!buffer.isEmpty() && (buffer.size() >= limits.maxBufferedRecords()
                || exceeds(bufferedBytes, frameBytes, limits.maxBufferedBytes()))) {
            flushBuffer();
        }
        Entry entry = new Entry(key, value, nextSequence++);
        buffer.add(entry);
        bufferedBytes += frameBytes;
        appendedRecords++;
        observe();
    }

    /**
     * Seals all runs, performs bounded fan-in merge passes, verifies the final run, and returns a
     * single-use streaming cursor. The complete final run is verified before this method returns.
     * Consumers must stage reversible local state and commit it only after {@link SortedCursor#next()}
     * returns {@code null}; an early close or cursor failure is not a successful consumption.
     */
    SortedCursor finish() throws LineageSpoolException {
        requireState(State.APPENDING);
        state = State.FINISHING;
        try {
            flushBuffer();
            if (runs.isEmpty()) {
                runs.add(writeEntriesRun(List.of(), 0, Stage.RUN_FORCED, Stage.RUN_VERIFIED));
            }
            List<Run> current = new ArrayList<>(runs);
            int generation = current.stream().mapToInt(value -> value.generation).max().orElse(0);
            while (current.size() > 1) {
                generation++;
                List<Run> next = new ArrayList<>();
                for (int offset = 0; offset < current.size(); offset += limits.mergeFanIn()) {
                    int end = Math.min(current.size(), offset + limits.mergeFanIn());
                    List<Run> group = new ArrayList<>(current.subList(offset, end));
                    if (group.size() == 1) {
                        next.add(group.get(0));
                        continue;
                    }
                    Run merged = mergeRuns(group, generation);
                    next.add(merged);
                    for (Run consumed : group) {
                        deleteOwnedRun(consumed, true);
                    }
                }
                current = next;
            }
            Run result = verifyRun(current.get(0));
            verifyFinalSequenceCoverage(result);
            reach(Stage.FINAL_VERIFIED, result.path, Code.RUN_READ_FAILED);
            state = State.SEALED;
            issuedCursor = new SortedCursor(result);
            return issuedCursor;
        } catch (LineageSpoolException exception) {
            state = State.FAILED;
            throw exception;
        } catch (RuntimeException exception) {
            state = State.FAILED;
            throw failure(Code.STATE_INVALID);
        }
    }

    @Override
    public void close() throws LineageSpoolException {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSING;
        if (issuedCursor != null && deferredCursorFailure == null) {
            deferredCursorFailure = issuedCursor.closeForOwner();
        }
        boolean clean = true;
        for (Map.Entry<Path, OwnedFile> owned : new ArrayList<>(ownedFiles.entrySet())) {
            clean &= cleanupOwnedFile(owned.getKey(), owned.getValue());
        }
        clean &= cleanupSession();
        state = clean ? State.CLOSED : State.CLEANUP_PENDING;
        observeQuietly();
        if (!clean) {
            throw failure(Code.CLEANUP_INCOMPLETE);
        }
        if (deferredCursorFailure != null) {
            throw failure(deferredCursorFailure);
        }
    }

    /**
     * Aborts an issued cursor after the owner has already selected a semantic failure.
     *
     * <p>Unlike {@link #close()}, an intentionally unconsumed cursor does not add a synthetic
     * {@link Code#STATE_INVALID} failure. Owned-file identity and binding checks still run for
     * every deletion, and an incomplete cleanup remains observable as
     * {@link Code#CLEANUP_INCOMPLETE}.</p>
     */
    void closeAfterFailure() throws LineageSpoolException {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSING;
        if (issuedCursor != null) {
            issuedCursor.abortForOwner();
        }
        boolean clean = true;
        for (Map.Entry<Path, OwnedFile> owned : new ArrayList<>(ownedFiles.entrySet())) {
            clean &= cleanupOwnedFile(owned.getKey(), owned.getValue());
        }
        clean &= cleanupSession();
        state = clean ? State.CLOSED : State.CLEANUP_PENDING;
        observeQuietly();
        if (!clean) {
            throw failure(Code.CLEANUP_INCOMPLETE);
        }
    }

    private void flushBuffer() throws LineageSpoolException {
        if (buffer.isEmpty()) {
            return;
        }
        compactBeforeInitialRun();
        buffer.sort(ENTRY_COMPARATOR);
        Run run = writeEntriesRun(buffer, 0, Stage.RUN_FORCED, Stage.RUN_VERIFIED);
        runs.add(run);
        buffer.clear();
        bufferedBytes = 0L;
        observe();
    }

    /** Keeps one live-file slot available for the next immutable run or merge output. */
    private void compactBeforeInitialRun() throws LineageSpoolException {
        while (ownedFiles.size() >= limits.maxLiveRunFiles() - 1) {
            if (runs.size() < 2) {
                throw failure(Code.SPOOL_LIMIT_EXCEEDED);
            }
            int count = Math.min(limits.mergeFanIn(), runs.size());
            List<Run> group = new ArrayList<>(runs.subList(0, count));
            int generation = group.stream().mapToInt(value -> value.generation).max().orElse(0) + 1;
            Run merged = mergeRuns(group, generation);
            for (Run consumed : group) {
                deleteOwnedRun(consumed, true);
            }
            runs.subList(0, count).clear();
            runs.add(0, merged);
        }
    }

    private Run writeEntriesRun(List<Entry> entries,
                                int generation,
                                Stage forcedStage,
                                Stage verifiedStage) throws LineageSpoolException {
        long frames = 0L;
        for (Entry entry : entries) {
            frames = addExactBounded(frames,
                    frameBytes(entry.key.length, entry.value.length), Long.MAX_VALUE);
        }
        RunOutput output = createRunOutput(entries.size(), frames, generation);
        try {
            for (Entry entry : entries) {
                output.write(entry);
            }
            Run run = output.finish();
            reach(forcedStage, run.path, Code.RUN_SYNC_FAILED);
            run = verifyRun(run);
            reach(verifiedStage, run.path, Code.RUN_READ_FAILED);
            return run;
        } catch (LineageSpoolException exception) {
            output.closeQuietly();
            throw exception;
        } catch (IOException | RuntimeException exception) {
            output.closeQuietly();
            throw failure(Code.RUN_WRITE_FAILED);
        }
    }

    private Run mergeRuns(List<Run> inputs, int generation) throws LineageSpoolException {
        List<Run> verifiedInputs = new ArrayList<>(inputs.size());
        for (Run input : inputs) {
            verifiedInputs.add(verifyRun(input));
        }
        long count = 0L;
        long frames = 0L;
        for (Run input : verifiedInputs) {
            count = addExactBounded(count, input.recordCount, limits.maxRecords());
            frames = addExactBounded(frames, input.frameBytes, Long.MAX_VALUE);
        }
        RunOutput output = createRunOutput(count, frames, generation);
        List<MergeInput> readers = new ArrayList<>();
        PriorityQueue<MergeHead> queue = new PriorityQueue<>((left, right) -> {
            int compared = ENTRY_COMPARATOR.compare(left.entry, right.entry);
            return compared != 0 ? compared : Integer.compare(left.index, right.index);
        });
        try {
            for (int index = 0; index < verifiedInputs.size(); index++) {
                Run input = verifiedInputs.get(index);
                reach(Stage.BEFORE_MERGE_INPUT, input.path, Code.RUN_READ_FAILED);
                MergeInput reader = openMergeInput(input);
                readers.add(reader);
                Entry first = reader.next();
                if (first != null) {
                    queue.add(new MergeHead(reader, first, index));
                } else {
                    reader.closeVerified();
                }
            }
            while (!queue.isEmpty()) {
                MergeHead head = queue.remove();
                output.write(head.entry);
                Entry next = head.input.next();
                if (next == null) {
                    head.input.closeVerified();
                } else {
                    queue.add(new MergeHead(head.input, next, head.index));
                }
            }
            Run merged = output.finish();
            reach(Stage.MERGE_FORCED, merged.path, Code.RUN_SYNC_FAILED);
            merged = verifyRun(merged);
            reach(Stage.RUN_VERIFIED, merged.path, Code.RUN_READ_FAILED);
            return merged;
        } catch (LineageSpoolException exception) {
            output.closeQuietly();
            closeMergeInputsQuietly(readers);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            output.closeQuietly();
            closeMergeInputsQuietly(readers);
            throw failure(Code.RUN_WRITE_FAILED);
        }
    }

    private MergeInput openMergeInput(Run run) throws LineageSpoolException {
        requireCurrentRun(run);
        RunReader reader = null;
        boolean counted = false;
        try {
            reader = new RunReader(run.path, limits, session.sessionId,
                    run.runId, run.generation, run.recordCount, run.frameBytes, run.sha256);
            openMergeReaders++;
            counted = true;
            observe();
            return new MergeInput(run, reader);
        } catch (LineageSpoolException exception) {
            rollbackMergeInputOpen(reader, counted);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            rollbackMergeInputOpen(reader, counted);
            throw failure(Code.RUN_READ_FAILED);
        }
    }

    private void rollbackMergeInputOpen(RunReader reader, boolean counted) {
        closeQuietly(reader);
        if (counted) {
            openMergeReaders--;
            observeQuietly();
        }
    }

    private void closeMergeInputsQuietly(List<MergeInput> readers) {
        for (MergeInput reader : readers) {
            reader.closeQuietly();
        }
    }

    private RunOutput createRunOutput(long recordCount, long frameBytes, int generation)
            throws LineageSpoolException {
        if (recordCount < 0 || recordCount > limits.maxRecords() || frameBytes < 0
                || generation < 0) {
            throw failure(Code.SPOOL_LIMIT_EXCEEDED);
        }
        long physicalBytes = physicalBytes(frameBytes);
        if (exceeds(activeDiskBytes, physicalBytes, limits.maxSpoolBytes())
                || ownedFiles.size() >= limits.maxLiveRunFiles()
                || createdRunFiles >= limits.maxCreatedRunFiles()) {
            throw failure(Code.SPOOL_LIMIT_EXCEEDED);
        }
        revalidateDirectories();
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            byte[] runId = randomBytes();
            Path path = session.path.resolve(
                    RUN_PREFIX + HexFormat.of().formatHex(runId) + RUN_SUFFIX).normalize();
            if (!isDirectRunPath(path)) {
                throw failure(Code.RUN_CREATE_FAILED);
            }
            FileChannel channel = null;
            try {
                channel = openNewPrivateFile(path);
                FileSnapshot created = snapshotFile(path, Code.RUN_CREATE_FAILED);
                OwnedFile owned = new OwnedFile(created, physicalBytes, runId,
                        generation, recordCount, frameBytes);
                ownedFiles.put(path, owned);
                activeDiskBytes += physicalBytes;
                createdRunFiles++;
                observe();
                return new RunOutput(path, channel, owned, recordCount, frameBytes,
                        runId, generation);
            } catch (FileAlreadyExistsException exception) {
                closeQuietly(channel);
            } catch (LineageSpoolException exception) {
                boolean createSucceeded = channel != null;
                closeQuietly(channel);
                if (createSucceeded && !ownedFiles.containsKey(path)) {
                    cleanupNewEmptyRun(path);
                }
                throw exception;
            } catch (IOException | RuntimeException exception) {
                boolean createSucceeded = channel != null;
                closeQuietly(channel);
                if (createSucceeded && !ownedFiles.containsKey(path)) {
                    cleanupNewEmptyRun(path);
                }
                throw failure(Code.RUN_CREATE_FAILED);
            }
        }
        throw failure(Code.RUN_CREATE_FAILED);
    }

    private Run verifyRun(Run expected) throws LineageSpoolException {
        requireCurrentRun(expected);
        FileSnapshot before = snapshotFile(expected.path, Code.RUN_READ_FAILED);
        try {
            long count = 0L;
            try (RunReader reader = new RunReader(expected.path, limits, session.sessionId,
                    expected.runId, expected.generation, expected.recordCount,
                    expected.frameBytes, expected.sha256)) {
                Entry previous = null;
                while (true) {
                    Entry entry = reader.next();
                    if (entry == null) {
                        break;
                    }
                    if (previous != null && ENTRY_COMPARATOR.compare(previous, entry) >= 0) {
                        throw failure(Code.RUN_CORRUPT);
                    }
                    previous = entry;
                    count++;
                }
            }
            // Close before taking the final snapshot, including on validation failures.
            FileSnapshot after = snapshotFile(expected.path, Code.RUN_READ_FAILED);
            if (!before.sameFileAndContent(after)
                    || count != expected.recordCount) {
                throw failure(Code.RUN_CORRUPT);
            }
            OwnedFile owned = ownedFiles.get(expected.path);
            if (owned == null || !owned.snapshot.sameIdentity(after.identity)) {
                throw failure(Code.RUN_CORRUPT);
            }
            owned.snapshot = after;
            return new Run(expected.path, after, expected.recordCount, expected.frameBytes,
                    expected.runId.clone(), expected.generation, expected.sha256.clone());
        } catch (LineageSpoolException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.RUN_CORRUPT);
        }
    }

    private void verifyFinalSequenceCoverage(Run run) throws LineageSpoolException {
        requireCurrentRun(run);
        SequenceCoverage coverage = new SequenceCoverage(appendedRecords);
        RunReader reader = null;
        try {
            reader = new RunReader(run.path, limits, session.sessionId, run.runId,
                    run.generation, run.recordCount, run.frameBytes, run.sha256);
            while (true) {
                Entry entry = reader.next();
                if (entry == null) {
                    break;
                }
                coverage.accept(entry.sequence);
            }
            reader.close();
            reader = null;
            requireCurrentRun(run);
            coverage.verifyComplete();
        } catch (LineageSpoolException exception) {
            closeQuietly(reader);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            closeQuietly(reader);
            throw failure(Code.RUN_CORRUPT);
        }
    }

    /** Tracks the exact append-sequence permutation required from a verified final run. */
    static final class SequenceCoverage {

        private final long expectedRecords;
        private final BitSet observed;
        private long count;

        SequenceCoverage(long expectedRecords) throws LineageSpoolException {
            if (expectedRecords < 0 || expectedRecords > HARD_MAX_RECORDS
                    || expectedRecords > Integer.MAX_VALUE) {
                throw failure(Code.RUN_CORRUPT);
            }
            this.expectedRecords = expectedRecords;
            this.observed = new BitSet((int) expectedRecords);
        }

        void accept(long sequence) throws LineageSpoolException {
            if (sequence < 0 || sequence >= expectedRecords
                    || sequence > Integer.MAX_VALUE) {
                throw failure(Code.RUN_CORRUPT);
            }
            int value = (int) sequence;
            if (count >= expectedRecords || observed.get(value)) {
                throw failure(Code.RUN_CORRUPT);
            }
            observed.set(value);
            count++;
        }

        void verifyComplete() throws LineageSpoolException {
            if (count != expectedRecords || observed.cardinality() != expectedRecords
                    || observed.nextClearBit(0) < expectedRecords) {
                throw failure(Code.RUN_CORRUPT);
            }
        }
    }

    private void requireCurrentRun(Run run) throws LineageSpoolException {
        if (run == null || !isDirectRunPath(run.path)) {
            throw failure(Code.RUN_READ_FAILED);
        }
        revalidateDirectories();
        FileSnapshot current = snapshotFile(run.path, Code.RUN_READ_FAILED);
        if (!run.snapshot.sameFileAndContent(current)) {
            throw failure(Code.RUN_CORRUPT);
        }
    }

    private void deleteOwnedRun(Run run, boolean strict) throws LineageSpoolException {
        OwnedFile owned = ownedFiles.get(run.path);
        if (owned == null || !cleanupOwnedFile(run.path, owned)) {
            if (strict) {
                throw failure(Code.CLEANUP_INCOMPLETE);
            }
        }
    }

    private boolean cleanupOwnedFile(Path path, OwnedFile owned) {
        if (path == null || owned == null || !isDirectRunPath(path)
                || !owned.snapshot.deletionAuthorized) {
            return false;
        }
        try {
            reach(Stage.BEFORE_DELETE, path, Code.CLEANUP_INCOMPLETE);
            revalidateDirectories();
            BasicFileAttributes attributes = readBasic(path);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(path)
                    || !owned.snapshot.sameIdentity(identitySource.identity(path, attributes))
                    || (owned.bindingEstablished
                    ? !hasOwnedHeaderBinding(path, owned) : attributes.size() != 0L)) {
                return false;
            }
            attributes = readBasic(path);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(path)
                    || !owned.snapshot.sameIdentity(identitySource.identity(path, attributes))) {
                return false;
            }
            Files.delete(path);
            ownedFiles.remove(path);
            if (!releaseActiveDiskBytes(owned.reservedBytes)) {
                return false;
            }
            observeQuietly();
            return true;
        } catch (NoSuchFileException exception) {
            ownedFiles.remove(path);
            if (!releaseActiveDiskBytes(owned.reservedBytes)) {
                return false;
            }
            observeQuietly();
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void cleanupNewEmptyRun(Path path) {
        // CREATE_NEW proves this session created the entry, but without the first identity
        // snapshot normal identity-authorized cleanup is unavailable. Delete only while the
        // directories, direct random name, regular-file type, and zero length are still proven.
        try {
            revalidateDirectories();
            if (!isDirectRunPath(path)) {
                return;
            }
            BasicFileAttributes attributes = readBasic(path);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || attributes.size() != 0L
                    || isReparsePoint(path)) {
                return;
            }
            Files.delete(path);
        } catch (IOException | RuntimeException ignored) {
            // An uncertain opaque entry is safer retained than deleted by path alone.
        }
    }

    private boolean releaseActiveDiskBytes(long reservedBytes) {
        if (reservedBytes < 0L || activeDiskBytes < reservedBytes) {
            return false;
        }
        activeDiskBytes -= reservedBytes;
        return true;
    }

    private boolean hasOwnedHeaderBinding(Path path, OwnedFile owned) {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] header = new byte[HEADER_BYTES];
            ByteBuffer target = ByteBuffer.wrap(header);
            while (target.hasRemaining()) {
                int read = channel.read(target);
                if (read <= 0) {
                    return false;
                }
            }
            ByteBuffer parsed = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            byte[] magic = new byte[RUN_MAGIC.length];
            byte[] headerSessionId = new byte[BINDING_BYTES];
            byte[] headerRunId = new byte[BINDING_BYTES];
            parsed.get(magic);
            parsed.get(headerSessionId);
            parsed.get(headerRunId);
            return Arrays.equals(magic, RUN_MAGIC)
                    && MessageDigest.isEqual(headerSessionId, session.sessionId)
                    && MessageDigest.isEqual(headerRunId, owned.runId)
                    && parsed.getInt() == owned.generation
                    && parsed.getLong() == owned.recordCount
                    && parsed.getLong() == owned.frameBytes;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean cleanupSession() {
        if (!ownedFiles.isEmpty() || session == null || session.path.getFileName() == null
                || !SESSION_NAME.matcher(session.path.getFileName().toString()).matches()
                || !session.snapshot.deletionAuthorized) {
            return false;
        }
        try {
            DirectorySnapshot currentWorkspace = snapshotDirectory(workspace.path,
                    Code.WORKSPACE_INVALID);
            DirectorySnapshot currentSession = snapshotDirectory(session.path,
                    Code.CLEANUP_INCOMPLETE);
            if (!workspace.snapshot.sameIdentity(currentWorkspace.snapshot.identity)
                    || !session.snapshot.sameIdentity(currentSession.snapshot.identity)) {
                return false;
            }
            Files.delete(session.path);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private DirectorySnapshot validateWorkspace(Path supplied) throws LineageSpoolException {
        if (supplied == null) {
            throw failure(Code.WORKSPACE_REQUIRED);
        }
        try {
            if (supplied.getFileSystem().provider() != FileSystems.getDefault().provider()
                    || !"file".equalsIgnoreCase(supplied.getFileSystem().provider().getScheme())
                    || !supplied.isAbsolute() || !supplied.equals(supplied.normalize())) {
                throw failure(Code.WORKSPACE_INVALID);
            }
            URI uri = supplied.toUri();
            if (uri.getAuthority() != null || isNetworkOrDevicePath(supplied.toString())) {
                throw failure(Code.FILESYSTEM_UNSUPPORTED);
            }
            Path root = supplied.getRoot();
            if (root == null) {
                throw failure(Code.WORKSPACE_INVALID);
            }
            requireDirectory(root);
            Path current = root;
            for (Path part : supplied) {
                current = current.resolve(part);
                requireDirectory(current);
            }
            PrivacyStatus privacy = privacyVerifier.verify(supplied);
            if (privacy == PrivacyStatus.UNSUPPORTED) {
                throw failure(Code.FILESYSTEM_UNSUPPORTED);
            }
            if (privacy != PrivacyStatus.PRIVATE) {
                throw failure(Code.WORKSPACE_NOT_PRIVATE);
            }
            Path real = supplied.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.isAbsolute() || !real.equals(real.normalize())) {
                throw failure(Code.WORKSPACE_INVALID);
            }
            return snapshotDirectory(real, Code.WORKSPACE_INVALID);
        } catch (LineageSpoolException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.WORKSPACE_INVALID);
        }
    }

    private SessionSnapshot createSession(DirectorySnapshot parent)
            throws LineageSpoolException {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            byte[] sessionId = randomBytes();
            Path path = parent.path.resolve(
                    SESSION_PREFIX + HexFormat.of().formatHex(sessionId)).normalize();
            if (!isDirectSessionPath(path, parent.path)) {
                throw failure(Code.SESSION_CREATE_FAILED);
            }
            boolean directoryCreated = false;
            DirectorySnapshot created = null;
            try {
                createPrivateDirectory(path, parent.path);
                directoryCreated = true;
                created = snapshotDirectory(path, Code.SESSION_CREATE_FAILED);
                PrivacyStatus privacy = privacyVerifier.verify(path);
                if (privacy != PrivacyStatus.PRIVATE) {
                    throw failure(privacy == PrivacyStatus.UNSUPPORTED
                            ? Code.FILESYSTEM_UNSUPPORTED : Code.WORKSPACE_NOT_PRIVATE);
                }
                DirectorySnapshot currentParent = snapshotDirectory(parent.path,
                        Code.WORKSPACE_INVALID);
                if (!parent.snapshot.sameIdentity(currentParent.snapshot.identity)) {
                    throw failure(Code.WORKSPACE_INVALID);
                }
                return new SessionSnapshot(created.path, created.snapshot, sessionId);
            } catch (FileAlreadyExistsException exception) {
                // Random collision: never inspect or replace the existing entry.
            } catch (LineageSpoolException exception) {
                if (directoryCreated && !cleanupNewEmptySession(path, created, parent)) {
                    throw failure(Code.CLEANUP_INCOMPLETE);
                }
                throw exception;
            } catch (IOException | RuntimeException exception) {
                if (directoryCreated && !cleanupNewEmptySession(path, created, parent)) {
                    throw failure(Code.CLEANUP_INCOMPLETE);
                }
                throw failure(Code.SESSION_CREATE_FAILED);
            }
        }
        throw failure(Code.SESSION_CREATE_FAILED);
    }

    private boolean cleanupNewEmptySession(Path path,
                                           DirectorySnapshot expected,
                                           DirectorySnapshot parent) {
        try {
            if (!isDirectSessionPath(path, parent.path)) {
                return false;
            }
            DirectorySnapshot currentParent = snapshotDirectory(
                    parent.path, Code.WORKSPACE_INVALID);
            if (!parent.snapshot.sameIdentity(currentParent.snapshot.identity)) {
                return false;
            }
            DirectorySnapshot actual = snapshotDirectory(path, Code.SESSION_CREATE_FAILED);
            if (expected != null
                    && !expected.snapshot.sameIdentity(actual.snapshot.identity)) {
                return false;
            }
            Files.delete(path);
            return true;
        } catch (IOException | RuntimeException ignored) {
            // An uncertain entry is safer left private than deleted by path alone.
            return false;
        }
    }

    private void revalidateDirectories() throws LineageSpoolException {
        DirectorySnapshot currentWorkspace = snapshotDirectory(workspace.path,
                Code.WORKSPACE_INVALID);
        DirectorySnapshot currentSession = snapshotDirectory(session.path,
                Code.WORKSPACE_INVALID);
        if (!workspace.snapshot.sameIdentity(currentWorkspace.snapshot.identity)
                || !session.snapshot.sameIdentity(currentSession.snapshot.identity)
                || session.path.getParent() == null
                || !session.path.getParent().equals(workspace.path)) {
            throw failure(Code.WORKSPACE_INVALID);
        }
        requirePrivateNow(workspace.path);
        requirePrivateNow(session.path);
    }

    private void requirePrivateNow(Path path) throws LineageSpoolException {
        try {
            PrivacyStatus privacy = privacyVerifier.verify(path);
            if (privacy == PrivacyStatus.UNSUPPORTED) {
                throw failure(Code.FILESYSTEM_UNSUPPORTED);
            }
            if (privacy != PrivacyStatus.PRIVATE) {
                throw failure(Code.WORKSPACE_NOT_PRIVATE);
            }
        } catch (LineageSpoolException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.WORKSPACE_NOT_PRIVATE);
        }
    }

    private DirectorySnapshot snapshotDirectory(Path path, Code code)
            throws LineageSpoolException {
        try {
            BasicFileAttributes attributes = readBasic(path);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(path)) {
                throw failure(Code.INDIRECT_PATH_FORBIDDEN);
            }
            Object identity = identitySource.identity(path, attributes);
            if (identity == null) {
                throw failure(Code.FILESYSTEM_UNSUPPORTED);
            }
            return new DirectorySnapshot(path, new FileSnapshot(identity,
                    identitySource.authorizesDeletion(), attributes.size(),
                    attributes.lastModifiedTime(), attributes.creationTime()));
        } catch (LineageSpoolException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(code);
        }
    }

    private FileSnapshot snapshotFile(Path path, Code code) throws LineageSpoolException {
        try {
            BasicFileAttributes attributes = readBasic(path);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(path)) {
                throw failure(code);
            }
            Object identity = identitySource.identity(path, attributes);
            if (identity == null) {
                throw failure(Code.FILESYSTEM_UNSUPPORTED);
            }
            return new FileSnapshot(identity, identitySource.authorizesDeletion(),
                    attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
        } catch (LineageSpoolException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(code);
        }
    }

    private void requireDirectory(Path path) throws LineageSpoolException {
        try {
            BasicFileAttributes attributes = readBasic(path);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(path)) {
                throw failure(Code.INDIRECT_PATH_FORBIDDEN);
            }
        } catch (LineageSpoolException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.WORKSPACE_INVALID);
        }
    }

    private void createPrivateDirectory(Path path, Path parent) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                parent, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null) {
            Files.createDirectory(path);
            return;
        }
        FileAttribute<Set<PosixFilePermission>> mode = PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Files.createDirectory(path, mode);
    }

    private FileChannel openNewPrivateFile(Path path) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributeView posix = Files.getFileAttributeView(
                session.path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null) {
            return FileChannel.open(path, options);
        }
        FileAttribute<Set<PosixFilePermission>> mode = PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return FileChannel.open(path, options, mode);
    }

    private boolean isDirectRunPath(Path path) {
        return path != null && path.getParent() != null && path.getParent().equals(session.path)
                && path.getFileName() != null
                && RUN_NAME.matcher(path.getFileName().toString()).matches();
    }

    private static boolean isDirectSessionPath(Path path, Path parent) {
        return path != null && parent != null && path.getParent() != null
                && path.getParent().equals(parent) && path.getFileName() != null
                && SESSION_NAME.matcher(path.getFileName().toString()).matches();
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return bytes;
    }

    private void reach(Stage stage, Path run, Code failureCode) throws LineageSpoolException {
        try {
            faultHook.reached(stage, session.path, run);
        } catch (IOException | RuntimeException exception) {
            throw failure(failureCode);
        }
    }

    private void observe() throws LineageSpoolException {
        try {
            observer.observed(new Metrics(bufferedBytes, buffer.size(), openMergeReaders,
                    activeDiskBytes));
        } catch (RuntimeException exception) {
            throw failure(Code.STATE_INVALID);
        }
    }

    private void observeQuietly() {
        try {
            observer.observed(new Metrics(bufferedBytes, buffer.size(), openMergeReaders,
                    activeDiskBytes));
        } catch (RuntimeException ignored) {
            // Cleanup remains best effort and never exposes observer internals.
        }
    }

    private void requireState(State expected) throws LineageSpoolException {
        if (state != expected) {
            throw failure(Code.STATE_INVALID);
        }
    }

    private static Limits validateLimits(Limits limits) throws LineageSpoolException {
        if (limits == null || limits.maxKeyBytes() <= 0
                || limits.maxKeyBytes() > HARD_MAX_ENTRY_BYTES
                || limits.maxValueBytes() < 0
                || limits.maxValueBytes() > HARD_MAX_ENTRY_BYTES
                || limits.maxRecords() <= 0 || limits.maxRecords() > HARD_MAX_RECORDS
                || limits.maxBufferedBytes() <= FRAME_FIXED_BYTES
                || limits.maxBufferedBytes() > HARD_MAX_BUFFERED_BYTES
                || limits.maxBufferedRecords() <= 0
                || limits.maxBufferedRecords() > HARD_MAX_BUFFERED_RECORDS
                || limits.mergeFanIn() < 2 || limits.mergeFanIn() > HARD_MAX_MERGE_FAN_IN
                || limits.maxSpoolBytes() < HEADER_BYTES + FOOTER_BYTES
                || limits.maxSpoolBytes() > HARD_MAX_SPOOL_BYTES
                || limits.maxLiveRunFiles() < limits.mergeFanIn() + 1
                || limits.maxLiveRunFiles() > HARD_MAX_LIVE_RUN_FILES
                || limits.maxCreatedRunFiles() < limits.maxLiveRunFiles()
                || limits.maxCreatedRunFiles() > HARD_MAX_CREATED_RUN_FILES) {
            throw failure(Code.LIMIT_CONFIGURATION_INVALID);
        }
        return limits;
    }

    private static long frameBytes(int keyBytes, int valueBytes) {
        return FRAME_FIXED_BYTES + (long) keyBytes + valueBytes;
    }

    private static long physicalBytes(long frameBytes) throws LineageSpoolException {
        long bodyAndFooter = addExactBounded(
                frameBytes, FOOTER_BYTES, Long.MAX_VALUE);
        return addExactBounded(HEADER_BYTES, bodyAndFooter, Long.MAX_VALUE);
    }

    private static long addExactBounded(long current, long value, long maximum)
            throws LineageSpoolException {
        if (current < 0 || value < 0 || maximum < 0 || current > maximum
                || value > maximum - current) {
            throw failure(Code.SPOOL_LIMIT_EXCEEDED);
        }
        return current + value;
    }

    private static boolean exceeds(long current, long addition, long ceiling) {
        return current < 0 || addition < 0 || ceiling < 0 || current > ceiling
                || addition > ceiling - current;
    }

    private static BasicFileAttributes readBasic(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isReparsePoint(Path path) throws IOException {
        DosFileAttributeView view = Files.getFileAttributeView(
                path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return false;
        }
        Object value = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
        if (!(value instanceof Number)) {
            throw new IOException();
        }
        return ((((Number) value).intValue()) & 0x400) != 0;
    }

    private static boolean isNetworkOrDevicePath(String rawPath) {
        String normalized = rawPath.replace('/', '\\');
        return normalized.startsWith("\\\\") || normalized.startsWith("\\\\?\\")
                || normalized.startsWith("\\\\.\\");
    }

    private static MessageDigest sha256() throws LineageSpoolException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw failure(Code.RUN_WRITE_FAILED);
        }
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    private static int readInt(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static long readLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The public failure remains sanitized.
            }
        }
    }

    private static void closeQuietly(RunReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // The public failure remains sanitized.
            }
        }
    }

    private static LineageSpoolException failure(Code code) {
        return new LineageSpoolException(code);
    }

    record Limits(int maxKeyBytes,
                  int maxValueBytes,
                  long maxRecords,
                  long maxBufferedBytes,
                  int maxBufferedRecords,
                  int mergeFanIn,
                  long maxSpoolBytes,
                  int maxLiveRunFiles,
                  int maxCreatedRunFiles) {
    }

    enum Stage {
        HEADER_PARTIALLY_WRITTEN,
        RUN_FORCED,
        RUN_VERIFIED,
        BEFORE_MERGE_INPUT,
        MERGE_FORCED,
        FINAL_VERIFIED,
        BEFORE_DELETE
    }

    record Metrics(long bufferedBytes,
                   int bufferedRecords,
                   int openMergeReaders,
                   long activeDiskBytes) {
    }

    interface FaultHook {
        FaultHook NOOP = (stage, session, run) -> { };

        void reached(Stage stage, Path session, Path run) throws IOException;
    }

    interface Observer {
        Observer NOOP = metrics -> { };

        void observed(Metrics metrics);
    }

    interface FileIdentitySource {
        FileIdentitySource STRICT = new FileIdentitySource() {
            @Override
            public Object identity(Path path, BasicFileAttributes attributes) {
                return attributes.fileKey();
            }

            @Override
            public boolean authorizesDeletion() {
                return true;
            }
        };

        Object identity(Path path, BasicFileAttributes attributes) throws IOException;

        boolean authorizesDeletion();
    }

    interface WorkspacePrivacyVerifier {
        WorkspacePrivacyVerifier POSIX_OWNER_ONLY = path -> {
            PosixFileAttributeView view = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                return PrivacyStatus.UNSUPPORTED;
            }
            PosixFileAttributes attributes = view.readAttributes();
            Set<PosixFilePermission> permissions = attributes.permissions();
            boolean ownerAccess = permissions.contains(PosixFilePermission.OWNER_READ)
                    && permissions.contains(PosixFilePermission.OWNER_WRITE)
                    && permissions.contains(PosixFilePermission.OWNER_EXECUTE);
            boolean shared = permissions.stream().anyMatch(value ->
                    value.name().startsWith("GROUP_") || value.name().startsWith("OTHERS_"));
            return ownerAccess && !shared ? PrivacyStatus.PRIVATE : PrivacyStatus.NOT_PRIVATE;
        };

        PrivacyStatus verify(Path path) throws IOException;
    }

    enum PrivacyStatus {
        PRIVATE,
        NOT_PRIVATE,
        UNSUPPORTED
    }

    /** A single sorted result. Accessors return copies so callers cannot mutate cursor state. */
    static final class Entry {
        private final byte[] key;
        private final byte[] value;
        private final long sequence;

        private Entry(byte[] key, byte[] value, long sequence) {
            this.key = key.clone();
            this.value = value.clone();
            this.sequence = sequence;
        }

        byte[] key() {
            return key.clone();
        }

        byte[] value() {
            return value.clone();
        }

        long sequence() {
            return sequence;
        }

        @Override
        public String toString() {
            return "Entry[redacted]";
        }
    }

    final class SortedCursor implements AutoCloseable {
        private final Run run;
        private RunReader reader;
        private boolean exhausted;
        private boolean closed;

        private SortedCursor(Run run) throws LineageSpoolException {
            this.run = run;
            requireCurrentRun(run);
            try {
                this.reader = new RunReader(run.path, limits, session.sessionId,
                        run.runId, run.generation, run.recordCount, run.frameBytes, run.sha256);
            } catch (IOException | RuntimeException exception) {
                throw failure(Code.RUN_READ_FAILED);
            }
        }

        Entry next() throws LineageSpoolException {
            if (closed) {
                throw failure(Code.STATE_INVALID);
            }
            if (exhausted) {
                return null;
            }
            try {
                Entry next = reader.next();
                if (next == null) {
                    reader.close();
                    reader = null;
                    requireCurrentRun(run);
                    exhausted = true;
                }
                return next;
            } catch (LineageSpoolException exception) {
                failCursor();
                throw exception;
            } catch (IOException | RuntimeException exception) {
                failCursor();
                throw failure(Code.RUN_CORRUPT);
            }
        }

        @Override
        public void close() throws LineageSpoolException {
            if (closed) {
                return;
            }
            if (!exhausted) {
                failCursor();
                throw failure(Code.STATE_INVALID);
            }
            Code terminalFailure = closeForOwner();
            if (terminalFailure != null) {
                throw failure(terminalFailure);
            }
        }

        private Code closeForOwner() {
            if (closed) {
                return null;
            }
            closed = true;
            closeQuietly(reader);
            reader = null;
            try {
                requireCurrentRun(run);
            } catch (LineageSpoolException | RuntimeException exception) {
                return Code.RUN_CORRUPT;
            }
            return exhausted ? null : Code.STATE_INVALID;
        }

        private void abortForOwner() {
            if (closed) {
                return;
            }
            closed = true;
            closeQuietly(reader);
            reader = null;
        }

        private void failCursor() {
            closed = true;
            closeQuietly(reader);
            reader = null;
            state = State.FAILED;
        }

        @Override
        public String toString() {
            return "SortedCursor[redacted]";
        }
    }

    private final class RunOutput {
        private final Path path;
        private final FileChannel channel;
        private final OwnedFile owned;
        private final long expectedRecords;
        private final long expectedFrames;
        private final byte[] runId;
        private final int generation;
        private final MessageDigest digest;
        private long records;
        private long frames;
        private boolean closed;

        private RunOutput(Path path,
                          FileChannel channel,
                          OwnedFile owned,
                          long expectedRecords,
                          long expectedFrames,
                          byte[] runId,
                          int generation) throws IOException, LineageSpoolException {
            this.path = path;
            this.channel = channel;
            this.owned = owned;
            this.expectedRecords = expectedRecords;
            this.expectedFrames = expectedFrames;
            this.runId = runId.clone();
            this.generation = generation;
            this.digest = sha256();
            writeDigest(RUN_MAGIC);
            reach(Stage.HEADER_PARTIALLY_WRITTEN, path, Code.RUN_WRITE_FAILED);
            writeDigest(session.sessionId);
            writeDigest(this.runId);
            writeDigest(intBytes(generation));
            writeDigest(longBytes(expectedRecords));
            writeDigest(longBytes(expectedFrames));
            owned.bindingEstablished = true;
        }

        private void write(Entry entry) throws IOException, LineageSpoolException {
            if (closed) {
                throw failure(Code.STATE_INVALID);
            }
            byte[] keyLength = intBytes(entry.key.length);
            byte[] valueLength = intBytes(entry.value.length);
            byte[] sequence = longBytes(entry.sequence);
            writeDigest(keyLength);
            writeDigest(valueLength);
            writeDigest(sequence);
            writeDigest(entry.key);
            writeDigest(entry.value);
            frames = addExactBounded(frames,
                    frameBytes(entry.key.length, entry.value.length), expectedFrames);
            records = addExactBounded(records, 1L, expectedRecords);
        }

        private Run finish() throws IOException, LineageSpoolException {
            if (closed || records != expectedRecords || frames != expectedFrames) {
                throw failure(Code.RUN_WRITE_FAILED);
            }
            byte[] checksum = digest.digest();
            writeRaw(intBytes(FOOTER_MARKER));
            writeRaw(session.sessionId);
            writeRaw(runId);
            writeRaw(intBytes(generation));
            writeRaw(longBytes(records));
            writeRaw(longBytes(frames));
            writeRaw(checksum);
            channel.force(true);
            channel.close();
            closed = true;
            FileSnapshot complete = snapshotFile(path, Code.RUN_SYNC_FAILED);
            if (!owned.snapshot.sameIdentity(complete.identity)
                    || complete.size != physicalBytes(frames)) {
                throw failure(Code.RUN_SYNC_FAILED);
            }
            owned.snapshot = complete;
            return new Run(path, complete, records, frames, runId.clone(), generation,
                    checksum.clone());
        }

        private void writeDigest(byte[] bytes) throws IOException {
            writeRaw(bytes);
            digest.update(bytes);
        }

        private void writeRaw(byte[] bytes) throws IOException {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                int written = channel.write(source);
                if (written <= 0) {
                    throw new IOException();
                }
            }
        }

        private void closeQuietly() {
            if (!closed) {
                BoundedExternalSortSpool.closeQuietly(channel);
                closed = true;
            }
        }
    }

    private final class MergeInput {
        private final Run run;
        private final RunReader reader;
        private boolean closed;

        private MergeInput(Run run, RunReader reader) {
            this.run = run;
            this.reader = reader;
        }

        private Entry next() throws IOException, LineageSpoolException {
            return reader.next();
        }

        private void closeVerified() throws LineageSpoolException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                reader.close();
            } catch (IOException exception) {
                decrementOpenReader();
                throw failure(Code.RUN_READ_FAILED);
            }
            decrementOpenReader();
            requireCurrentRun(run);
        }

        private void closeQuietly() {
            if (!closed) {
                closed = true;
                BoundedExternalSortSpool.closeQuietly(reader);
                decrementOpenReaderQuietly();
            }
        }
    }

    private void decrementOpenReader() throws LineageSpoolException {
        openMergeReaders--;
        observe();
    }

    private void decrementOpenReaderQuietly() {
        openMergeReaders--;
        observeQuietly();
    }

    private static final class RunReader implements AutoCloseable {
        private final FileChannel channel;
        private final Limits limits;
        private final MessageDigest digest;
        private final byte[] expectedSessionId;
        private final byte[] expectedRunId;
        private final int expectedGeneration;
        private final long expectedRecords;
        private final long expectedFrames;
        private final byte[] expectedSha256;
        private long records;
        private long frames;
        private boolean footerRead;
        private boolean closed;

        private RunReader(Path path,
                          Limits limits,
                          byte[] expectedSessionId,
                          byte[] expectedRunId,
                          int expectedGeneration,
                          long expectedRecords,
                          long expectedFrames,
                          byte[] expectedSha256) throws IOException, LineageSpoolException {
            this.channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            boolean initialized = false;
            try {
                this.limits = limits;
                this.expectedSessionId = expectedSessionId.clone();
                this.expectedRunId = expectedRunId.clone();
                this.expectedGeneration = expectedGeneration;
                this.expectedRecords = expectedRecords;
                this.expectedFrames = expectedFrames;
                this.expectedSha256 = expectedSha256.clone();
                this.digest = sha256();
                byte[] magic = readDigest(RUN_MAGIC.length);
                if (!Arrays.equals(magic, RUN_MAGIC)) {
                    throw failure(Code.RUN_CORRUPT);
                }
                byte[] sessionId = readDigest(BINDING_BYTES);
                byte[] runId = readDigest(BINDING_BYTES);
                int generation = readInt(readDigest(Integer.BYTES));
                long declaredRecords = readLong(readDigest(Long.BYTES));
                long declaredFrames = readLong(readDigest(Long.BYTES));
                if (!MessageDigest.isEqual(sessionId, this.expectedSessionId)
                        || !MessageDigest.isEqual(runId, this.expectedRunId)
                        || generation != expectedGeneration
                        || declaredRecords != expectedRecords || declaredFrames != expectedFrames
                        || declaredRecords < 0 || declaredRecords > limits.maxRecords()
                        || declaredFrames < 0) {
                    throw failure(Code.RUN_CORRUPT);
                }
                initialized = true;
            } finally {
                if (!initialized) {
                    BoundedExternalSortSpool.closeQuietly(channel);
                }
            }
        }

        private Entry next() throws IOException, LineageSpoolException {
            if (closed || footerRead) {
                return null;
            }
            byte[] keyLengthBytes = readExact(Integer.BYTES);
            int keyLength = readInt(keyLengthBytes);
            if (keyLength == FOOTER_MARKER) {
                byte[] footerSessionId = readExact(BINDING_BYTES);
                byte[] footerRunId = readExact(BINDING_BYTES);
                int footerGeneration = readInt(readExact(Integer.BYTES));
                long declaredRecords = readLong(readExact(Long.BYTES));
                long declaredFrames = readLong(readExact(Long.BYTES));
                byte[] declaredDigest = readExact(32);
                ByteBuffer trailing = ByteBuffer.allocate(1);
                if (channel.read(trailing) != -1
                        || !MessageDigest.isEqual(footerSessionId, expectedSessionId)
                        || !MessageDigest.isEqual(footerRunId, expectedRunId)
                        || footerGeneration != expectedGeneration
                        || declaredRecords != expectedRecords
                        || declaredFrames != expectedFrames
                        || declaredRecords != records || declaredFrames != frames
                        || !MessageDigest.isEqual(declaredDigest, expectedSha256)
                        || !MessageDigest.isEqual(declaredDigest, digest.digest())) {
                    throw failure(Code.RUN_CORRUPT);
                }
                footerRead = true;
                return null;
            }
            if (keyLength <= 0 || keyLength > limits.maxKeyBytes()) {
                throw failure(Code.RUN_CORRUPT);
            }
            digest.update(keyLengthBytes);
            byte[] valueLengthBytes = readExact(Integer.BYTES);
            digest.update(valueLengthBytes);
            int valueLength = readInt(valueLengthBytes);
            if (valueLength < 0 || valueLength > limits.maxValueBytes()) {
                throw failure(Code.RUN_CORRUPT);
            }
            byte[] sequenceBytes = readExact(Long.BYTES);
            digest.update(sequenceBytes);
            long sequence = readLong(sequenceBytes);
            if (sequence < 0 || sequence >= limits.maxRecords()) {
                throw failure(Code.RUN_CORRUPT);
            }
            byte[] key = readExact(keyLength);
            byte[] value = readExact(valueLength);
            digest.update(key);
            digest.update(value);
            frames = addExactBounded(frames,
                    BoundedExternalSortSpool.frameBytes(keyLength, valueLength), Long.MAX_VALUE);
            records = addExactBounded(records, 1L, limits.maxRecords());
            return new Entry(key, value, sequence);
        }

        private byte[] readDigest(int size) throws IOException, LineageSpoolException {
            byte[] bytes = readExact(size);
            digest.update(bytes);
            return bytes;
        }

        private byte[] readExact(int size) throws IOException, LineageSpoolException {
            byte[] bytes = new byte[size];
            ByteBuffer target = ByteBuffer.wrap(bytes);
            while (target.hasRemaining()) {
                int read = channel.read(target);
                if (read < 0) {
                    throw failure(Code.RUN_CORRUPT);
                }
                if (read == 0) {
                    int single = channel.read(ByteBuffer.wrap(bytes,
                            target.position(), Math.min(1, target.remaining())));
                    if (single <= 0) {
                        throw failure(Code.RUN_CORRUPT);
                    }
                    target.position(target.position() + single);
                }
            }
            return bytes;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                channel.close();
            }
        }
    }

    private static final class MergeHead {
        private final MergeInput input;
        private final Entry entry;
        private final int index;

        private MergeHead(MergeInput input, Entry entry, int index) {
            this.input = input;
            this.entry = entry;
            this.index = index;
        }
    }

    private record Run(Path path,
                       FileSnapshot snapshot,
                       long recordCount,
                       long frameBytes,
                       byte[] runId,
                       int generation,
                       byte[] sha256) {
    }

    private static final class OwnedFile {
        private FileSnapshot snapshot;
        private final long reservedBytes;
        private final byte[] runId;
        private final int generation;
        private final long recordCount;
        private final long frameBytes;
        private boolean bindingEstablished;

        private OwnedFile(FileSnapshot snapshot,
                          long reservedBytes,
                          byte[] runId,
                          int generation,
                          long recordCount,
                          long frameBytes) {
            this.snapshot = snapshot;
            this.reservedBytes = reservedBytes;
            this.runId = runId.clone();
            this.generation = generation;
            this.recordCount = recordCount;
            this.frameBytes = frameBytes;
        }
    }

    private record DirectorySnapshot(Path path, FileSnapshot snapshot) {
    }

    private static final class SessionSnapshot {
        private final Path path;
        private final FileSnapshot snapshot;
        private final byte[] sessionId;

        private SessionSnapshot(Path path, FileSnapshot snapshot, byte[] sessionId) {
            this.path = path;
            this.snapshot = snapshot;
            this.sessionId = sessionId.clone();
        }
    }

    private static final class FileSnapshot {
        private final Object identity;
        private final boolean deletionAuthorized;
        private final long size;
        private final FileTime modified;
        private final FileTime created;

        private FileSnapshot(Object identity,
                             boolean deletionAuthorized,
                             long size,
                             FileTime modified,
                             FileTime created) {
            this.identity = identity;
            this.deletionAuthorized = deletionAuthorized;
            this.size = size;
            this.modified = modified;
            this.created = created;
        }

        private boolean sameIdentity(Object other) {
            return deletionAuthorized && identity != null && other != null
                    && identity.equals(other);
        }

        private boolean sameFileAndContent(FileSnapshot other) {
            return other != null && identity != null && other.identity != null
                    && identity.equals(other.identity) && size == other.size
                    && modified.equals(other.modified) && created.equals(other.created);
        }
    }

    private enum State {
        APPENDING,
        FINISHING,
        SEALED,
        FAILED,
        CLOSING,
        CLEANUP_PENDING,
        CLOSED
    }
}
