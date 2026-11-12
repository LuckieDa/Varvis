package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.nbscn.varvis.variantanalysis.lineage.LineageSpoolException.Code;

class BoundedExternalSortSpoolTest {

    private static final String PRIVACY_CANARY =
            "patient-Alice-C:\\private\\spool\\secret-token";
    private static final long EMPTY_RUN_BYTES = 148L;
    private static final long ONE_BYTE_KEY_VALUE_RUN_BYTES = 166L;
    private static final long TWO_ONE_BYTE_KEY_VALUE_RUN_BYTES = 184L;
    private static final long TWO_INPUT_MERGE_PEAK_BYTES =
            ONE_BYTE_KEY_VALUE_RUN_BYTES * 2 + TWO_ONE_BYTE_KEY_VALUE_RUN_BYTES;
    private static final long RUN_KEY_LENGTH_OFFSET = 60L;
    private static final long ONE_RECORD_FOOTER_SESSION_OFFSET = 82L;
    private static final BoundedExternalSortSpool.WorkspacePrivacyVerifier PRIVATE_WORKSPACE =
            ignored -> BoundedExternalSortSpool.PrivacyStatus.PRIVATE;

    @Test
    void sortsZeroAndOneRecord(@TempDir Path parent) throws Exception {
        Path emptyWorkspace = workspace(parent, "empty");
        try (BoundedExternalSortSpool spool = spool(emptyWorkspace, standardLimits())) {
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();

            assertNull(cursor.next());
            assertNull(cursor.next());
            cursor.close();
        }
        assertWorkspaceEmpty(emptyWorkspace);

        Path oneWorkspace = workspace(parent, "one");
        try (BoundedExternalSortSpool spool = spool(oneWorkspace, standardLimits())) {
            spool.append(bytes(7), bytes(9));

            List<BoundedExternalSortSpool.Entry> entries = drain(spool.finish());

            assertEquals(1, entries.size());
            assertArrayEquals(bytes(7), entries.get(0).key());
            assertArrayEquals(bytes(9), entries.get(0).value());
            assertEquals(0L, entries.get(0).sequence());
        }
        assertWorkspaceEmpty(oneWorkspace);
    }

    @Test
    void ordersUnsignedPrefixKeysAndKeepsEqualKeysStableAcrossRunsAndMergePasses(
            @TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "ordering");
        RecordingProbe probe = new RecordingProbe();
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                8, 8, 32, 64, 1, 2, 1_000_000, 5, 100);
        List<byte[]> keys = List.of(
                bytes(0xff), bytes(0x01), bytes(0x01, 0x00), bytes(0x80), bytes(0x00),
                bytes(0x01), bytes(0x7f), bytes(0x01), bytes(0xfe), bytes(0x01, 0xff),
                bytes(0x01), bytes(0x81));

        List<BoundedExternalSortSpool.Entry> entries;
        try (BoundedExternalSortSpool spool = spool(workspace, limits, probe, probe)) {
            for (int index = 0; index < keys.size(); index++) {
                spool.append(keys.get(index), bytes(index));
            }
            entries = drain(spool.finish());
        }

        assertEquals(List.of(4L, 1L, 5L, 7L, 10L, 2L, 9L, 6L, 3L, 11L, 8L, 0L),
                entries.stream().map((BoundedExternalSortSpool.Entry value) -> value.sequence())
                        .collect(Collectors.toList()));
        assertEquals(List.of(1L, 5L, 7L, 10L), entries.stream()
                .filter(entry -> Arrays.equals(bytes(0x01), entry.key()))
                .map((BoundedExternalSortSpool.Entry value) -> value.sequence())
                .collect(Collectors.toList()));
        assertTrue(probe.mergeOutputs >= 2, "the fixture must exercise multiple merge passes");
        assertTrue(probe.maximumOpenMergeReaders <= limits.mergeFanIn());
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void finalSequenceCoverageRejectsDuplicateMissingAndOutOfRangeSequences()
            throws Exception {
        BoundedExternalSortSpool.SequenceCoverage complete =
                new BoundedExternalSortSpool.SequenceCoverage(2);
        complete.accept(1);
        complete.accept(0);
        assertDoesNotThrow(complete::verifyComplete);

        BoundedExternalSortSpool.SequenceCoverage empty =
                new BoundedExternalSortSpool.SequenceCoverage(0);
        assertDoesNotThrow(empty::verifyComplete);

        BoundedExternalSortSpool.SequenceCoverage duplicate =
                new BoundedExternalSortSpool.SequenceCoverage(2);
        duplicate.accept(0);
        assertCode(Code.RUN_CORRUPT, () -> duplicate.accept(0));

        BoundedExternalSortSpool.SequenceCoverage missing =
                new BoundedExternalSortSpool.SequenceCoverage(2);
        missing.accept(0);
        assertCode(Code.RUN_CORRUPT, missing::verifyComplete);

        BoundedExternalSortSpool.SequenceCoverage outOfRange =
                new BoundedExternalSortSpool.SequenceCoverage(2);
        assertCode(Code.RUN_CORRUPT, () -> outOfRange.accept(-1));
        assertCode(Code.RUN_CORRUPT, () -> outOfRange.accept(2));
        assertCode(Code.RUN_CORRUPT,
                () -> new BoundedExternalSortSpool.SequenceCoverage(-1));
    }

    @Test
    void defensivelyCopiesAppendInputsAndCursorOutputs(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "copies");
        byte[] key = bytes(2);
        byte[] value = bytes(9);

        try (BoundedExternalSortSpool spool = spool(workspace, standardLimits())) {
            spool.append(key, value);
            key[0] = 0;
            value[0] = 0;

            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
            BoundedExternalSortSpool.Entry entry = cursor.next();
            assertNotNull(entry);
            assertArrayEquals(bytes(2), entry.key());
            assertArrayEquals(bytes(9), entry.value());

            byte[] exposedKey = entry.key();
            byte[] exposedValue = entry.value();
            exposedKey[0] = 1;
            exposedValue[0] = 1;
            assertArrayEquals(bytes(2), entry.key());
            assertArrayEquals(bytes(9), entry.value());
            assertNull(cursor.next());
            cursor.close();
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void enforcesExactAndNPlusOneKeyAndValueLimits(@TempDir Path parent) throws Exception {
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                4, 4, 8, 64, 4, 2, 10_000, 3, 20);
        Path exactWorkspace = workspace(parent, "entry-exact");
        try (BoundedExternalSortSpool spool = spool(exactWorkspace, limits)) {
            spool.append(new byte[4], new byte[4]);
            assertEquals(1, drain(spool.finish()).size());
        }

        Path keyWorkspace = workspace(parent, "key-n-plus-one");
        try (BoundedExternalSortSpool spool = spool(keyWorkspace, limits)) {
            assertCode(Code.SPOOL_LIMIT_EXCEEDED,
                    () -> spool.append(new byte[5], new byte[4]));
            assertCode(Code.STATE_INVALID, () -> spool.append(bytes(1), bytes(1)));
        }

        Path valueWorkspace = workspace(parent, "value-n-plus-one");
        try (BoundedExternalSortSpool spool = spool(valueWorkspace, limits)) {
            assertCode(Code.SPOOL_LIMIT_EXCEEDED,
                    () -> spool.append(new byte[4], new byte[5]));
            assertCode(Code.STATE_INVALID, spool::finish);
        }
    }

    @Test
    void enforcesExactAndNPlusOneRecordLimit(@TempDir Path parent) throws Exception {
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                4, 4, 2, 64, 2, 2, 10_000, 3, 20);
        Path exactWorkspace = workspace(parent, "records-exact");
        try (BoundedExternalSortSpool spool = spool(exactWorkspace, limits)) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            assertEquals(2, drain(spool.finish()).size());
        }

        Path overflowWorkspace = workspace(parent, "records-n-plus-one");
        try (BoundedExternalSortSpool spool = spool(overflowWorkspace, limits)) {
            spool.append(bytes(1), bytes(1));
            spool.append(bytes(2), bytes(2));
            assertCode(Code.SPOOL_LIMIT_EXCEEDED,
                    () -> spool.append(bytes(3), bytes(3)));
            assertCode(Code.STATE_INVALID, spool::finish);
        }
    }

    @Test
    void enforcesExactAndNPlusOneBufferLimitsAndSpillsBeforeExceedingThem(
            @TempDir Path parent) throws Exception {
        BoundedExternalSortSpool.Limits byteLimits = new BoundedExternalSortSpool.Limits(
                2, 2, 10, 36, 10, 2, 10_000, 3, 30);
        Path byteWorkspace = workspace(parent, "buffer-byte-exact");
        RecordingProbe byteProbe = new RecordingProbe();
        try (BoundedExternalSortSpool spool = spool(
                byteWorkspace, byteLimits, byteProbe, byteProbe)) {
            spool.append(bytes(1), bytes(1));
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(0), bytes(0));
            assertEquals(3, drain(spool.finish()).size());
        }
        assertEquals(byteLimits.maxBufferedBytes(), byteProbe.maximumBufferedBytes);
        assertEquals(2, byteProbe.maximumBufferedRecords);
        assertTrue(byteProbe.createdRuns >= 2,
                "the byte-limit N+1 record must trigger a spill before retention");

        BoundedExternalSortSpool.Limits recordLimits = new BoundedExternalSortSpool.Limits(
                2, 2, 10, 100, 2, 2, 10_000, 3, 30);
        Path recordWorkspace = workspace(parent, "buffer-record-exact");
        RecordingProbe recordProbe = new RecordingProbe();
        try (BoundedExternalSortSpool spool = spool(
                recordWorkspace, recordLimits, recordProbe, recordProbe)) {
            spool.append(bytes(1), bytes(1));
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(0), bytes(0));
            assertEquals(3, drain(spool.finish()).size());
        }
        assertEquals(recordLimits.maxBufferedRecords(), recordProbe.maximumBufferedRecords);
        assertEquals(36L, recordProbe.maximumBufferedBytes);
        assertTrue(recordProbe.createdRuns >= 2,
                "the record-limit N+1 record must trigger a spill before retention");

        Path overflowWorkspace = workspace(parent, "buffer-n-plus-one");
        BoundedExternalSortSpool.Limits singleEntryLimits =
                new BoundedExternalSortSpool.Limits(
                        2, 2, 10, 18, 2, 2, 10_000, 3, 30);
        try (BoundedExternalSortSpool spool = spool(
                overflowWorkspace, singleEntryLimits)) {
            assertCode(Code.SPOOL_LIMIT_EXCEEDED,
                    () -> spool.append(bytes(1, 2), bytes(3)));
            assertCode(Code.STATE_INVALID, () -> spool.append(bytes(1), bytes(1)));
        }
    }

    @Test
    void reachesButNeverExceedsLiveRunAndReaderLimits(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "live-runs");
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                2, 2, 20, 18, 1, 2, 1_000_000, 3, 100);
        RecordingProbe probe = new RecordingProbe();

        try (BoundedExternalSortSpool spool = spool(workspace, limits, probe, probe)) {
            for (int index = 0; index < 12; index++) {
                spool.append(bytes(12 - index), bytes(index));
            }
            assertEquals(12, drain(spool.finish()).size());
        }

        assertEquals(limits.maxLiveRunFiles(), probe.maximumLiveRuns);
        assertTrue(probe.maximumLiveRuns <= limits.maxLiveRunFiles());
        assertEquals(limits.mergeFanIn(), probe.maximumOpenMergeReaders);
        assertTrue(probe.maximumBufferedRecords <= limits.maxBufferedRecords());
        assertTrue(probe.maximumBufferedBytes <= limits.maxBufferedBytes());
        assertTrue(probe.maximumDiskBytes <= limits.maxSpoolBytes());
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void enforcesExactAndNPlusOneCreatedRunLimit(@TempDir Path parent) throws Exception {
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                2, 2, 8, 18, 1, 2, 10_000, 3, 3);
        Path exactWorkspace = workspace(parent, "created-exact");
        RecordingProbe exactProbe = new RecordingProbe();
        try (BoundedExternalSortSpool spool = spool(exactWorkspace, limits, exactProbe,
                exactProbe)) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            assertEquals(2, drain(spool.finish()).size());
        }
        assertEquals(limits.maxCreatedRunFiles(), exactProbe.createdRuns);

        Path overflowWorkspace = workspace(parent, "created-n-plus-one");
        RecordingProbe overflowProbe = new RecordingProbe();
        try (BoundedExternalSortSpool spool = spool(
                overflowWorkspace, limits, overflowProbe, overflowProbe)) {
            spool.append(bytes(3), bytes(3));
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            assertCode(Code.SPOOL_LIMIT_EXCEEDED, spool::finish);
            assertEquals(limits.maxCreatedRunFiles(), overflowProbe.createdRuns);
            assertCode(Code.STATE_INVALID, spool::finish);
        }
    }

    @Test
    void enforcesExactAndNPlusOnePhysicalDiskLimit(@TempDir Path parent) throws Exception {
        BoundedExternalSortSpool.Limits exactLimits = new BoundedExternalSortSpool.Limits(
                1, 1, 1, 18, 1, 2, ONE_BYTE_KEY_VALUE_RUN_BYTES, 3, 3);
        Path exactWorkspace = workspace(parent, "disk-exact");
        RecordingProbe exactProbe = new RecordingProbe();
        try (BoundedExternalSortSpool spool = spool(
                exactWorkspace, exactLimits, exactProbe, exactProbe)) {
            spool.append(bytes(1), bytes(1));
            assertEquals(1, drain(spool.finish()).size());
        }
        assertEquals(ONE_BYTE_KEY_VALUE_RUN_BYTES, exactProbe.maximumDiskBytes);

        BoundedExternalSortSpool.Limits overflowLimits =
                new BoundedExternalSortSpool.Limits(
                        1, 1, 1, 18, 1, 2,
                        ONE_BYTE_KEY_VALUE_RUN_BYTES - 1, 3, 3);
        Path overflowWorkspace = workspace(parent, "disk-n-plus-one");
        try (BoundedExternalSortSpool spool = spool(overflowWorkspace, overflowLimits)) {
            spool.append(bytes(1), bytes(1));
            assertCode(Code.SPOOL_LIMIT_EXCEEDED, spool::finish);
            assertCode(Code.STATE_INVALID, () -> spool.append(bytes(1), bytes(1)));
        }

        BoundedExternalSortSpool.Limits emptyExact = new BoundedExternalSortSpool.Limits(
                1, 1, 1, 18, 1, 2, EMPTY_RUN_BYTES, 3, 3);
        Path emptyWorkspace = workspace(parent, "disk-empty-exact");
        try (BoundedExternalSortSpool spool = spool(emptyWorkspace, emptyExact)) {
            assertTrue(drain(spool.finish()).isEmpty());
        }

        BoundedExternalSortSpool.Limits mergeExact = new BoundedExternalSortSpool.Limits(
                1, 1, 2, 18, 1, 2, TWO_INPUT_MERGE_PEAK_BYTES, 3, 3);
        Path mergeWorkspace = workspace(parent, "disk-merge-exact");
        RecordingProbe mergeProbe = new RecordingProbe();
        try (BoundedExternalSortSpool spool = spool(
                mergeWorkspace, mergeExact, mergeProbe, mergeProbe)) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            assertEquals(2, drain(spool.finish()).size());
        }
        assertEquals(TWO_INPUT_MERGE_PEAK_BYTES, mergeProbe.maximumDiskBytes);

        BoundedExternalSortSpool.Limits mergeOverflow = new BoundedExternalSortSpool.Limits(
                1, 1, 2, 18, 1, 2, TWO_INPUT_MERGE_PEAK_BYTES - 1, 3, 3);
        Path mergeOverflowWorkspace = workspace(parent, "disk-merge-n-plus-one");
        try (BoundedExternalSortSpool spool = spool(
                mergeOverflowWorkspace, mergeOverflow)) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            assertCode(Code.SPOOL_LIMIT_EXCEEDED, spool::finish);
        }

        Path deleteFailureWorkspace = workspace(parent, "disk-delete-failure");
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        RecordingProbe deleteFailureProbe = new RecordingProbe();
        BoundedExternalSortSpool.FaultHook deleteFailureHook = (stage, session, run) -> {
            deleteFailureProbe.reached(stage, session, run);
            if (stage == BoundedExternalSortSpool.Stage.BEFORE_DELETE
                    && failFirstDelete.compareAndSet(true, false)) {
                throw new IOException(PRIVACY_CANARY);
            }
        };
        try (BoundedExternalSortSpool spool = spool(
                deleteFailureWorkspace, mergeExact, deleteFailureHook, deleteFailureProbe)) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            assertCode(Code.CLEANUP_INCOMPLETE, spool::finish);
            assertEquals(TWO_INPUT_MERGE_PEAK_BYTES, deleteFailureProbe.currentDiskBytes,
                    "a failed delete must not refund reserved disk budget");
        }
    }

    @Test
    void appendWriteFailurePoisonsSpoolAndDoesNotExposeHookDetails(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "append-failure");
        BoundedExternalSortSpool.FaultHook failureHook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.RUN_FORCED) {
                throw new IOException(PRIVACY_CANARY);
            }
        };
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                2, 2, 8, 18, 1, 2, 10_000, 3, 20);

        try (BoundedExternalSortSpool spool = spool(
                workspace, limits, failureHook, BoundedExternalSortSpool.Observer.NOOP)) {
            spool.append(bytes(1), bytes(1));
            LineageSpoolException failure = assertCode(Code.RUN_SYNC_FAILED,
                    () -> spool.append(bytes(2), bytes(2)));
            assertSanitized(failure);
            assertCode(Code.STATE_INVALID, () -> spool.append(bytes(3), bytes(3)));
            assertCode(Code.STATE_INVALID, spool::finish);
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void finishFailurePoisonsSpool(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "finish-failure");
        BoundedExternalSortSpool.FaultHook failureHook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                throw new IOException(PRIVACY_CANARY);
            }
        };

        try (BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), failureHook,
                BoundedExternalSortSpool.Observer.NOOP)) {
            spool.append(bytes(1), bytes(1));
            assertCode(Code.RUN_READ_FAILED, spool::finish);
            assertCode(Code.STATE_INVALID, () -> spool.append(bytes(2), bytes(2)));
            assertCode(Code.STATE_INVALID, spool::finish);
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void finishCursorExhaustionAndCloseHaveStrictStates(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "states");
        BoundedExternalSortSpool spool = spool(workspace, standardLimits());
        spool.append(bytes(1), bytes(1));

        BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
        assertEquals("SortedCursor[redacted]", cursor.toString());
        assertCode(Code.STATE_INVALID, spool::finish);
        assertCode(Code.STATE_INVALID, () -> spool.append(bytes(2), bytes(2)));
        assertNotNull(cursor.next());
        assertNull(cursor.next());
        assertNull(cursor.next());
        cursor.close();
        assertDoesNotThrow(cursor::close);
        assertCode(Code.STATE_INVALID, cursor::next);

        spool.close();
        assertDoesNotThrow(spool::close);
        assertCode(Code.STATE_INVALID, spool::finish);
        assertCode(Code.STATE_INVALID, () -> spool.append(bytes(3), bytes(3)));
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void closingCursorBeforeExhaustionPoisonsConsumption(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "early-close");
        try (BoundedExternalSortSpool spool = spool(workspace, standardLimits())) {
            spool.append(bytes(1), bytes(1));
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();

            assertCode(Code.STATE_INVALID, cursor::close);
            assertCode(Code.STATE_INVALID, cursor::next);
            assertCode(Code.STATE_INVALID, spool::finish);
            assertCode(Code.STATE_INVALID, () -> spool.append(bytes(2), bytes(2)));
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void ownerCloseBeforeCursorExhaustionReportsAnUnsuccessfulConsumption(
            @TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "owner-early-close");
        BoundedExternalSortSpool spool = spool(workspace, standardLimits());
        spool.append(bytes(1), bytes(1));
        assertNotNull(spool.finish());

        LineageSpoolException failure = assertCode(Code.STATE_INVALID, spool::close);
        assertSanitized(failure);
        assertDoesNotThrow(spool::close);
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void closeAfterFailureAbortsUnexhaustedCursorAndRemovesPrivateWorkspace(
            @TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "owner-semantic-failure");
        BoundedExternalSortSpool spool = spool(workspace, standardLimits());
        try {
            spool.append(bytes(3), bytes(3));
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
            assertNotNull(cursor.next());

            assertDoesNotThrow(spool::closeAfterFailure);

            assertCode(Code.STATE_INVALID, cursor::next);
            assertDoesNotThrow(spool::closeAfterFailure);
            assertDoesNotThrow(spool::close);
        } finally {
            spool.closeAfterFailure();
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cursorCorruptionFailsClosedAndPoisonsOwner(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "cursor-corruption");
        AtomicReference<Path> finalRun = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook capture = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                finalRun.set(run);
            }
        };

        try (BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), capture,
                BoundedExternalSortSpool.Observer.NOOP)) {
            spool.append(bytes(1), bytes(1));
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
            Path run = finalRun.get();
            assertNotNull(run);
            try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                channel.position(76L);
                channel.write(ByteBuffer.wrap(bytes(2)));
                channel.force(true);
            }

            assertNotNull(cursor.next());
            assertCode(Code.RUN_CORRUPT, cursor::next);
            assertCode(Code.STATE_INVALID, cursor::next);
            assertCode(Code.STATE_INVALID, spool::finish);
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void rejectsTruncatedTrailingInvalidLengthAndFooterTampering(@TempDir Path parent)
            throws Exception {
        assertRunTamperFails(parent, "truncated", run -> {
            try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                channel.truncate(Files.size(run) - 1L);
                channel.force(true);
            }
        }, true);
        assertRunTamperFails(parent, "trailing-byte", run -> {
            try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                channel.position(Files.size(run));
                channel.write(ByteBuffer.wrap(bytes(1)));
                channel.force(true);
            }
        }, true);
        assertRunTamperFails(parent, "invalid-key-length", run -> {
            try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                channel.position(RUN_KEY_LENGTH_OFFSET);
                channel.write(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(Integer.MAX_VALUE).flip());
                channel.force(true);
            }
        }, false);
        assertRunTamperFails(parent, "footer-binding",
                run -> flipByte(run, ONE_RECORD_FOOTER_SESSION_OFFSET), true);
    }

    @Test
    void observerFailureIsSanitizedAndPoisonsAppend(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "observer-failure");
        BoundedExternalSortSpool.Observer observer = metrics -> {
            if (metrics.bufferedRecords() > 0) {
                throw new IllegalStateException(PRIVACY_CANARY);
            }
        };

        try (BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), BoundedExternalSortSpool.FaultHook.NOOP,
                observer)) {
            LineageSpoolException failure = assertCode(Code.STATE_INVALID,
                    () -> spool.append(bytes(1), bytes(1)));
            assertSanitized(failure);
            assertCode(Code.STATE_INVALID, spool::finish);
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void constructorObserverFailureRemovesPrivateEmptySession(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "constructor-observer-failure");
        BoundedExternalSortSpool.Observer observer = metrics -> {
            throw new IllegalStateException(PRIVACY_CANARY);
        };

        LineageSpoolException failure = assertCode(Code.STATE_INVALID,
                () -> spool(workspace, standardLimits(),
                        BoundedExternalSortSpool.FaultHook.NOOP, observer));

        assertSanitized(failure);
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void sessionSnapshotFailureRemovesTheNewEmptySession(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "session-snapshot-failure");
        AtomicBoolean failFirstSessionSnapshot = new AtomicBoolean(true);
        PortableTestFileIdentitySource baseIdentity = new PortableTestFileIdentitySource();
        BoundedExternalSortSpool.FileIdentitySource identitySource =
                new BoundedExternalSortSpool.FileIdentitySource() {
                    @Override
                    public Object identity(Path path, BasicFileAttributes attributes)
                            throws IOException {
                        if (attributes.isDirectory() && path.getFileName() != null
                                && path.getFileName().toString()
                                .startsWith(".analysis-allele-lineage-v1.spool.")
                                && failFirstSessionSnapshot.compareAndSet(true, false)) {
                            throw new IOException(PRIVACY_CANARY);
                        }
                        return baseIdentity.identity(path, attributes);
                    }

                    @Override
                    public boolean authorizesDeletion() {
                        return true;
                    }
                };

        LineageSpoolException failure = assertCode(Code.SESSION_CREATE_FAILED,
                () -> new BoundedExternalSortSpool(
                        workspace, standardLimits(), new SecureRandom(), identitySource,
                        PRIVATE_WORKSPACE, BoundedExternalSortSpool.FaultHook.NOOP,
                        BoundedExternalSortSpool.Observer.NOOP));

        assertSanitized(failure);
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void sessionPrivacyVerifierFailureRemovesTheNewEmptySession(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "session-privacy-failure");
        BoundedExternalSortSpool.WorkspacePrivacyVerifier verifier = path -> {
            if (path.equals(workspace)) {
                return BoundedExternalSortSpool.PrivacyStatus.PRIVATE;
            }
            throw new IOException(PRIVACY_CANARY);
        };

        LineageSpoolException failure = assertCode(Code.SESSION_CREATE_FAILED,
                () -> new BoundedExternalSortSpool(
                        workspace, standardLimits(), new SecureRandom(),
                        new PortableTestFileIdentitySource(), verifier,
                        BoundedExternalSortSpool.FaultHook.NOOP,
                        BoundedExternalSortSpool.Observer.NOOP));

        assertSanitized(failure);
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void observerFailureWhileOpeningMergeInputRollsBackReaderAndCount(
            @TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "merge-observer-failure");
        BoundedExternalSortSpool.Observer observer = metrics -> {
            if (metrics.openMergeReaders() > 0) {
                throw new IllegalStateException(PRIVACY_CANARY);
            }
        };
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                2, 2, 10, 18, 1, 2, 10_000, 3, 30);

        try (BoundedExternalSortSpool spool = spool(
                workspace, limits, BoundedExternalSortSpool.FaultHook.NOOP, observer)) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            LineageSpoolException failure = assertCode(Code.STATE_INVALID, spool::finish);
            assertSanitized(failure);
            assertCode(Code.STATE_INVALID, spool::finish);
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void corruptMergeHeaderClosesFailedReaderConstruction(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "corrupt-merge-header");
        AtomicBoolean corrupted = new AtomicBoolean();
        AtomicReference<Path> corruptedRun = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook hook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.BEFORE_MERGE_INPUT
                    && corrupted.compareAndSet(false, true)) {
                corruptedRun.set(run);
                FileTime modified = Files.getLastModifiedTime(run, LinkOption.NOFOLLOW_LINKS);
                try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                    channel.position(0L);
                    channel.write(ByteBuffer.wrap(bytes('X')));
                    channel.force(true);
                }
                Files.setLastModifiedTime(run, modified);
            }
        };
        BoundedExternalSortSpool.Limits limits = new BoundedExternalSortSpool.Limits(
                2, 2, 10, 18, 1, 2, 10_000, 3, 30);

        try (BoundedExternalSortSpool spool = spool(
                workspace, limits, hook, BoundedExternalSortSpool.Observer.NOOP)) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            LineageSpoolException failure = assertCode(Code.RUN_CORRUPT, spool::finish);
            assertSanitized(failure);
            assertTrue(corrupted.get());
            try (FileChannel channel = FileChannel.open(
                    corruptedRun.get(), StandardOpenOption.WRITE)) {
                channel.position(0L);
                channel.write(ByteBuffer.wrap(bytes('V')));
                channel.force(true);
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void privacyDriftFailsClosedBeforeFurtherDiskWork(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "privacy-drift");
        AtomicBoolean privateNow = new AtomicBoolean(true);
        BoundedExternalSortSpool.WorkspacePrivacyVerifier verifier = ignored ->
                privateNow.get() ? BoundedExternalSortSpool.PrivacyStatus.PRIVATE
                        : BoundedExternalSortSpool.PrivacyStatus.NOT_PRIVATE;
        BoundedExternalSortSpool spool = new BoundedExternalSortSpool(
                workspace, standardLimits(), new SecureRandom(),
                new PortableTestFileIdentitySource(), verifier,
                BoundedExternalSortSpool.FaultHook.NOOP, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            privateNow.set(false);
            LineageSpoolException failure = assertCode(
                    Code.WORKSPACE_NOT_PRIVATE, spool::finish);
            assertSanitized(failure);
            assertCode(Code.STATE_INVALID, spool::finish);
        } finally {
            spool.close();
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cleanupFailureCanBeRetriedWithoutReopeningTheSpool(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "cleanup-retry");
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        BoundedExternalSortSpool.FaultHook hook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.BEFORE_DELETE
                    && failFirstDelete.compareAndSet(true, false)) {
                throw new IOException(PRIVACY_CANARY);
            }
        };
        BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), hook, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            assertEquals(1, drain(spool.finish()).size());

            LineageSpoolException failure = assertCode(Code.CLEANUP_INCOMPLETE, spool::close);
            assertSanitized(failure);
            assertCode(Code.STATE_INVALID, spool::finish);
            assertCode(Code.STATE_INVALID, () -> spool.append(bytes(2), bytes(2)));
            assertDoesNotThrow(spool::close);
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.close();
                } catch (LineageSpoolException ignored) {
                    // The assertions below retain the authoritative cleanup result.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void closeAfterFailureExposesSanitizedCleanupFailureAndCanRetry(
            @TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "semantic-failure-cleanup-retry");
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        BoundedExternalSortSpool.FaultHook hook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.BEFORE_DELETE
                    && failFirstDelete.compareAndSet(true, false)) {
                throw new IOException(PRIVACY_CANARY);
            }
        };
        BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), hook, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
            assertNotNull(cursor.next());

            LineageSpoolException failure = assertCode(
                    Code.CLEANUP_INCOMPLETE, spool::closeAfterFailure);
            assertSanitized(failure);
            assertCode(Code.STATE_INVALID, cursor::next);
            assertFalse(failFirstDelete.get());
            assertDoesNotThrow(spool::closeAfterFailure);
            assertDoesNotThrow(spool::closeAfterFailure);
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.closeAfterFailure();
                } catch (LineageSpoolException ignored) {
                    // The assertions above retain the authoritative cleanup result.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cursorCorruptionRemainsVisibleAfterCleanupRetry(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "cursor-corruption-cleanup-retry");
        AtomicReference<Path> finalRun = new AtomicReference<>();
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        BoundedExternalSortSpool.FaultHook hook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                finalRun.set(run);
            }
            if (stage == BoundedExternalSortSpool.Stage.BEFORE_DELETE
                    && failFirstDelete.compareAndSet(true, false)) {
                throw new IOException(PRIVACY_CANARY);
            }
        };
        BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), hook, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            assertNotNull(spool.finish());
            Path run = finalRun.get();
            assertNotNull(run);
            try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                channel.position(76L);
                channel.write(ByteBuffer.wrap(bytes(2)));
                channel.force(true);
            }

            LineageSpoolException cleanupFailure = assertCode(
                    Code.CLEANUP_INCOMPLETE, spool::close);
            assertSanitized(cleanupFailure);
            LineageSpoolException cursorFailure = assertCode(Code.RUN_CORRUPT, spool::close);
            assertSanitized(cursorFailure);
            assertDoesNotThrow(spool::close);
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.close();
                } catch (LineageSpoolException ignored) {
                    // The assertions above retain the authoritative terminal results.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cleanupRetainsAnUnboundPartialHeaderUntilItIsProvenEmpty(
            @TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "partial-header-cleanup");
        AtomicReference<Path> partialRun = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook hook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.HEADER_PARTIALLY_WRITTEN) {
                partialRun.set(run);
                throw new IOException(PRIVACY_CANARY);
            }
        };
        BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), hook, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            LineageSpoolException writeFailure = assertCode(
                    Code.RUN_WRITE_FAILED, spool::finish);
            assertSanitized(writeFailure);
            Path run = partialRun.get();
            assertNotNull(run);
            assertEquals(8L, Files.size(run));

            LineageSpoolException cleanupFailure = assertCode(
                    Code.CLEANUP_INCOMPLETE, spool::close);
            assertSanitized(cleanupFailure);
            assertTrue(Files.exists(run, LinkOption.NOFOLLOW_LINKS));

            try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                channel.truncate(0L);
                channel.force(true);
            }
            assertDoesNotThrow(spool::close);
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.close();
                } catch (LineageSpoolException ignored) {
                    // The assertions above retain the authoritative cleanup result.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cleanupRequiresRunAndSessionHeaderBindingBeforeDeletion(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "cleanup-binding");
        AtomicReference<Path> finalRun = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook capture = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                finalRun.set(run);
            }
        };
        BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), capture, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            assertEquals(1, drain(spool.finish()).size());
            Path run = finalRun.get();
            assertNotNull(run);
            for (long offset : List.of(8L, 24L, 40L, 44L, 52L)) {
                flipByte(run, offset);
                LineageSpoolException failure = assertCode(
                        Code.CLEANUP_INCOMPLETE, spool::close);
                assertSanitized(failure);
                assertTrue(Files.exists(run, LinkOption.NOFOLLOW_LINKS));
                flipByte(run, offset);
            }
            assertDoesNotThrow(spool::close);
            assertFalse(Files.exists(run, LinkOption.NOFOLLOW_LINKS));
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.close();
                } catch (LineageSpoolException ignored) {
                    // The assertions below retain the authoritative cleanup result.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cleanupRechecksRunIdentityAfterReadingTheHeaderBinding(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "cleanup-second-identity");
        AtomicReference<Path> finalRun = new AtomicReference<>();
        AtomicBoolean cleanupArmed = new AtomicBoolean();
        AtomicBoolean driftOnSecondRead = new AtomicBoolean(true);
        AtomicInteger cleanupRunIdentityReads = new AtomicInteger();
        PortableTestFileIdentitySource baseIdentity = new PortableTestFileIdentitySource();
        BoundedExternalSortSpool.FileIdentitySource secondReadDriftIdentity =
                new BoundedExternalSortSpool.FileIdentitySource() {
                    @Override
                    public Object identity(Path path, BasicFileAttributes attributes)
                            throws IOException {
                        Object base = baseIdentity.identity(path, attributes);
                        Path target = finalRun.get();
                        boolean drifted = cleanupArmed.get() && driftOnSecondRead.get()
                                && target != null && target.equals(path)
                                && attributes.isRegularFile()
                                && cleanupRunIdentityReads.incrementAndGet() >= 2;
                        return List.of(base, drifted);
                    }

                    @Override
                    public boolean authorizesDeletion() {
                        return true;
                    }
                };
        BoundedExternalSortSpool.FaultHook hook = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                finalRun.set(run);
            }
            if (stage == BoundedExternalSortSpool.Stage.BEFORE_DELETE
                    && Objects.equals(finalRun.get(), run)) {
                cleanupRunIdentityReads.set(0);
                cleanupArmed.set(true);
            }
        };
        BoundedExternalSortSpool spool = new BoundedExternalSortSpool(
                workspace, standardLimits(), new SecureRandom(), secondReadDriftIdentity,
                PRIVATE_WORKSPACE, hook, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            assertEquals(1, drain(spool.finish()).size());
            Path run = finalRun.get();
            assertNotNull(run);

            LineageSpoolException failure = assertCode(
                    Code.CLEANUP_INCOMPLETE, spool::close);
            assertSanitized(failure);
            assertEquals(2, cleanupRunIdentityReads.get());
            assertTrue(Files.exists(run, LinkOption.NOFOLLOW_LINKS));

            driftOnSecondRead.set(false);
            assertDoesNotThrow(spool::close);
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.close();
                } catch (LineageSpoolException ignored) {
                    // The assertions above retain the authoritative cleanup result.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cleanupNeverDeletesAReplacementRunIdentity(@TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "cleanup-replacement");
        AtomicReference<Path> finalRun = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook capture = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                finalRun.set(run);
            }
        };
        AtomicBoolean replacementCreated = new AtomicBoolean();
        PortableTestFileIdentitySource baseIdentity = new PortableTestFileIdentitySource();
        BoundedExternalSortSpool.FileIdentitySource replacementAwareIdentity =
                new BoundedExternalSortSpool.FileIdentitySource() {
                    @Override
                    public Object identity(Path path, BasicFileAttributes attributes)
                            throws IOException {
                        return List.of(baseIdentity.identity(path, attributes),
                                attributes.isRegularFile() && replacementCreated.get());
                    }

                    @Override
                    public boolean authorizesDeletion() {
                        return true;
                    }
                };
        BoundedExternalSortSpool spool = new BoundedExternalSortSpool(
                workspace, standardLimits(), new SecureRandom(), replacementAwareIdentity,
                PRIVATE_WORKSPACE, capture, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            assertEquals(1, drain(spool.finish()).size());
            Path run = finalRun.get();
            assertNotNull(run);
            byte[] original = Files.readAllBytes(run);
            Files.delete(run);
            replacementCreated.set(true);
            Files.write(run, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            LineageSpoolException failure = assertCode(Code.CLEANUP_INCOMPLETE, spool::close);
            assertSanitized(failure);
            assertTrue(Files.exists(run, LinkOption.NOFOLLOW_LINKS));

            Files.delete(run);
            assertDoesNotThrow(spool::close);
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.close();
                } catch (LineageSpoolException ignored) {
                    // The assertions below retain the authoritative cleanup result.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void cursorSnapshotDriftReportsCorruptionAfterSuccessfulCleanup(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "cursor-drift-cleanup");
        AtomicReference<Path> finalRun = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook capture = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                finalRun.set(run);
            }
        };
        BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), capture, BoundedExternalSortSpool.Observer.NOOP);
        try {
            spool.append(bytes(1), bytes(1));
            assertNotNull(spool.finish());
            Path run = finalRun.get();
            assertNotNull(run);
            try (FileChannel channel = FileChannel.open(run, StandardOpenOption.WRITE)) {
                channel.position(76L);
                channel.write(ByteBuffer.wrap(bytes(2)));
                channel.force(true);
            }

            LineageSpoolException failure = assertCode(Code.RUN_CORRUPT, spool::close);
            assertSanitized(failure);
            assertDoesNotThrow(spool::close);
        } finally {
            if (Files.exists(workspace)) {
                try {
                    spool.close();
                } catch (LineageSpoolException ignored) {
                    // The assertions below retain the authoritative cleanup result.
                }
            }
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void exceptionEntryAndCursorStringsNeverExposePayloadOrPath(@TempDir Path parent)
            throws Exception {
        Path workspace = workspace(parent, "privacy-" + PRIVACY_CANARY.hashCode());
        try (BoundedExternalSortSpool spool = spool(workspace, standardLimits())) {
            byte[] payload = PRIVACY_CANARY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            spool.append(bytes(1), payload);
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
            BoundedExternalSortSpool.Entry entry = cursor.next();
            assertNotNull(entry);
            assertEquals("Entry[redacted]", entry.toString());
            assertFalse(entry.toString().contains(PRIVACY_CANARY));
            assertFalse(cursor.toString().contains(PRIVACY_CANARY));
            assertNull(cursor.next());
            cursor.close();
        }

        Path invalidWorkspace = workspace(parent, "privacy-failure");
        try (BoundedExternalSortSpool spool = spool(invalidWorkspace, standardLimits())) {
            LineageSpoolException failure = assertCode(
                    Code.ENTRY_INVALID, () -> spool.append(new byte[0], bytes(1)));
            assertSanitized(failure);
            assertFalse(failure.toString().contains(invalidWorkspace.toString()));
            assertFalse(failure.getMessage().contains(invalidWorkspace.toString()));
        }
    }

    @Test
    void injectedIdentityAndPrivacyVerifierSupportDefaultWindowsFilesystem(
            @TempDir Path parent) throws Exception {
        Path workspace = workspace(parent, "portable-injection");

        try (BoundedExternalSortSpool spool = spool(workspace, standardLimits())) {
            spool.append(bytes(2), bytes(2));
            spool.append(bytes(1), bytes(1));
            assertEquals(List.of(1L, 0L), drain(spool.finish()).stream()
                    .map((BoundedExternalSortSpool.Entry value) -> value.sequence())
                    .collect(Collectors.toList()));
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void posixDefaultCreatesOwnerOnlySessionAndRun(@TempDir Path parent) throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(
                parent, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null,
                "POSIX permissions unavailable");
        Path workspace = workspace(parent, "posix-default");
        Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwx------"));

        try (BoundedExternalSortSpool spool =
                     new BoundedExternalSortSpool(workspace, standardLimits())) {
            spool.append(bytes(1), bytes(1));
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
            Path session = onlyChild(workspace);
            Path run = onlyChild(session);

            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(session, LinkOption.NOFOLLOW_LINKS));
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(run, LinkOption.NOFOLLOW_LINKS));
            assertNotNull(cursor.next());
            assertNull(cursor.next());
            cursor.close();
        }
        assertWorkspaceEmpty(workspace);
    }

    @Test
    void defaultWindowsConstructorFailsClosedWithoutCreatingSession(@TempDir Path directory) {
        Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win"));

        LineageSpoolException failure = assertCode(Code.FILESYSTEM_UNSUPPORTED,
                () -> new BoundedExternalSortSpool(directory, standardLimits()));

        assertSanitized(failure);
        assertWorkspaceEmpty(directory);
    }

    @Test
    void invalidLimitConfigurationFailsBeforeCreatingSession(@TempDir Path directory) {
        BoundedExternalSortSpool.Limits invalid = new BoundedExternalSortSpool.Limits(
                0, 1, 1, 18, 1, 2, 1_000, 3, 3);

        assertCode(Code.LIMIT_CONFIGURATION_INVALID,
                () -> spool(directory, invalid));
        assertWorkspaceEmpty(directory);
    }

    private static BoundedExternalSortSpool spool(
            Path workspace,
            BoundedExternalSortSpool.Limits limits) throws LineageSpoolException {
        return spool(workspace, limits, BoundedExternalSortSpool.FaultHook.NOOP,
                BoundedExternalSortSpool.Observer.NOOP);
    }

    private static BoundedExternalSortSpool spool(
            Path workspace,
            BoundedExternalSortSpool.Limits limits,
            BoundedExternalSortSpool.FaultHook faultHook,
            BoundedExternalSortSpool.Observer observer) throws LineageSpoolException {
        return new BoundedExternalSortSpool(
                workspace, limits, new SecureRandom(), new PortableTestFileIdentitySource(),
                PRIVATE_WORKSPACE, faultHook, observer);
    }

    private static BoundedExternalSortSpool.Limits standardLimits() {
        return new BoundedExternalSortSpool.Limits(
                64, 256, 1_000, 512, 4, 2, 1_000_000, 5, 1_000);
    }

    private static List<BoundedExternalSortSpool.Entry> drain(
            BoundedExternalSortSpool.SortedCursor cursor) throws LineageSpoolException {
        List<BoundedExternalSortSpool.Entry> entries = new ArrayList<>();
        while (true) {
            BoundedExternalSortSpool.Entry entry = cursor.next();
            if (entry == null) {
                cursor.close();
                return entries;
            }
            entries.add(entry);
        }
    }

    private static void assertRunTamperFails(Path parent,
                                             String name,
                                             RunTamper tamper,
                                             boolean firstRecordReadable) throws Exception {
        Path workspace = workspace(parent, "tamper-" + name);
        AtomicReference<Path> finalRun = new AtomicReference<>();
        BoundedExternalSortSpool.FaultHook capture = (stage, session, run) -> {
            if (stage == BoundedExternalSortSpool.Stage.FINAL_VERIFIED) {
                finalRun.set(run);
            }
        };
        try (BoundedExternalSortSpool spool = spool(
                workspace, standardLimits(), capture,
                BoundedExternalSortSpool.Observer.NOOP)) {
            spool.append(bytes(1), bytes(1));
            BoundedExternalSortSpool.SortedCursor cursor = spool.finish();
            Path run = finalRun.get();
            assertNotNull(run);
            tamper.apply(run);
            if (firstRecordReadable) {
                assertNotNull(cursor.next());
            }
            LineageSpoolException failure = assertCode(Code.RUN_CORRUPT, cursor::next);
            assertSanitized(failure);
            assertCode(Code.STATE_INVALID, cursor::next);
        }
        assertWorkspaceEmpty(workspace);
    }

    private static void flipByte(Path path, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer current = ByteBuffer.allocate(1);
            channel.position(offset);
            if (channel.read(current) != 1) {
                throw new IOException();
            }
            channel.position(offset);
            channel.write(ByteBuffer.wrap(bytes(current.array()[0] ^ 0x01)));
            channel.force(true);
        }
    }

    private static LineageSpoolException assertCode(Code expected, Executable executable) {
        LineageSpoolException failure = assertThrows(LineageSpoolException.class, executable);
        assertEquals(expected, failure.code());
        return failure;
    }

    private static void assertSanitized(LineageSpoolException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(failure.code().name(), failure.getMessage());
        assertEquals("LineageSpoolException[" + failure.code().name() + "]",
                failure.toString());
        assertFalse(failure.getMessage().contains(PRIVACY_CANARY));
        assertFalse(failure.toString().contains(PRIVACY_CANARY));
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }

    private static Path workspace(Path parent, String name) throws IOException {
        return Files.createDirectory(parent.resolve(name));
    }

    private static Path onlyChild(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            List<Path> paths = children.collect(Collectors.toList());
            assertEquals(1, paths.size());
            return paths.get(0);
        }
    }

    private static void assertWorkspaceEmpty(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            assertEquals(0L, children.count());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingProbe
            implements BoundedExternalSortSpool.FaultHook, BoundedExternalSortSpool.Observer {
        private long maximumBufferedBytes;
        private int maximumBufferedRecords;
        private int maximumOpenMergeReaders;
        private long maximumDiskBytes;
        private long currentDiskBytes;
        private int maximumLiveRuns;
        private int createdRuns;
        private int mergeOutputs;

        @Override
        public void reached(BoundedExternalSortSpool.Stage stage, Path session, Path run)
                throws IOException {
            if (stage == BoundedExternalSortSpool.Stage.RUN_FORCED
                    || stage == BoundedExternalSortSpool.Stage.MERGE_FORCED) {
                createdRuns++;
            }
            if (stage == BoundedExternalSortSpool.Stage.MERGE_FORCED) {
                mergeOutputs++;
            }
            try (Stream<Path> children = Files.list(session)) {
                int live = (int) children
                        .filter(path -> path.getFileName().toString().startsWith("run-"))
                        .filter(path -> path.getFileName().toString().endsWith(".bin"))
                        .count();
                maximumLiveRuns = Math.max(maximumLiveRuns, live);
            }
        }

        @Override
        public void observed(BoundedExternalSortSpool.Metrics metrics) {
            maximumBufferedBytes = Math.max(maximumBufferedBytes, metrics.bufferedBytes());
            maximumBufferedRecords = Math.max(
                    maximumBufferedRecords, metrics.bufferedRecords());
            maximumOpenMergeReaders = Math.max(
                    maximumOpenMergeReaders, metrics.openMergeReaders());
            maximumDiskBytes = Math.max(maximumDiskBytes, metrics.activeDiskBytes());
            currentDiskBytes = metrics.activeDiskBytes();
        }
    }

    @FunctionalInterface
    private interface RunTamper {
        void apply(Path run) throws IOException;
    }

    /**
     * The default Windows provider may return a null fileKey. This test-only identity retains a
     * provider key when available and otherwise combines the normalized path with creation time.
     */
    private static final class PortableTestFileIdentitySource
            implements BoundedExternalSortSpool.FileIdentitySource {

        @Override
        public Object identity(Path path, BasicFileAttributes attributes) {
            Object providerKey = attributes.fileKey();
            return providerKey != null
                    ? new TestIdentity(providerKey, null, null, false)
                    : new TestIdentity(null, path.toAbsolutePath().normalize(),
                    attributes.creationTime(), attributes.isDirectory());
        }

        @Override
        public boolean authorizesDeletion() {
            return true;
        }
    }

    private static final class TestIdentity {
        private final Object providerKey;
        private final Path path;
        private final FileTime created;
        private final boolean directory;

        private TestIdentity(Object providerKey, Path path, FileTime created, boolean directory) {
            this.providerKey = providerKey;
            this.path = path;
            this.created = created;
            this.directory = directory;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TestIdentity)) {
                return false;
            }
            TestIdentity that = (TestIdentity) other;
            return Objects.equals(providerKey, that.providerKey)
                    && Objects.equals(path, that.path)
                    && Objects.equals(created, that.created)
                    && directory == that.directory;
        }

        @Override
        public int hashCode() {
            return Objects.hash(providerKey, path, created, directory);
        }
    }
}
