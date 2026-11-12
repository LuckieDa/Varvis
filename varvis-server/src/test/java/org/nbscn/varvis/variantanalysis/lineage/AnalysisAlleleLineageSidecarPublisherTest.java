package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageSidecarPublisher.CleanupStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageSidecarPublisher.DirectorySync;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageSidecarPublisher.FileIdentitySource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageSidecarPublisher.Outcome;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageSidecarPublisher.PublicationResult;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageSidecarPublisher.Stage;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisAlleleLineageSidecarPublisherTest {

    private static final String PRIVACY_CANARY =
            "patient-Alice-C:\\private\\workspace\\secret-token";
    private static final long TEST_TIMEOUT_SECONDS = 20L;

    @Test
    void publishesFixedCanonicalArtifactInRequiredDurabilityOrder(@TempDir Path directory)
            throws Exception {
        RecordingDirectorySync sync = new RecordingDirectorySync();
        List<Stage> stages = Collections.synchronizedList(new ArrayList<>());
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(sync,
                (stage, workspace, temporary, target) -> stages.add(stage));
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();

        PublicationResult result = publisher.publish(directory, expected);

        Path target = directory.resolve(AnalysisAlleleLineageSidecarPublisher.FILE_NAME);
        assertEquals(Outcome.CREATED, result.outcome());
        assertEquals(CleanupStatus.CLEAN, result.cleanupStatus());
        assertEquals(AnalysisAlleleLineageSidecarPublisher.FILE_NAME,
                result.logicalFileName());
        assertTrue(Files.isRegularFile(target));
        assertEquals(expected, result.verified().document());
        assertEquals(Files.size(target), result.verified().physicalBytes());
        assertEquals(64, result.verified().physicalSha256().length());
        assertEquals("PublicationResult[redacted]", result.toString());
        assertEquals("VerifiedSidecar[redacted]", result.verified().toString());
        assertFalse(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
                .contains("\r"));
        assertNoWriterTemporary(directory);
        assertEquals(2, sync.syncCount);
        assertEquals(List.of(
                Stage.WORKSPACE_VALIDATED,
                Stage.TEMP_CREATED,
                Stage.WRITE_COMPLETED,
                Stage.FILE_FORCED,
                Stage.FILE_CLOSED,
                Stage.TEMP_VERIFIED,
                Stage.BEFORE_PUBLISH,
                Stage.TARGET_LINKED,
                Stage.TARGET_VERIFIED,
                Stage.DIRECTORY_SYNCED,
                Stage.TEMP_DELETED,
                Stage.CLEANUP_DIRECTORY_SYNCED
        ), stages);
        assertEquals(expected, publisher.verifyExpected(directory, expected).document());
    }

    @Test
    void exactExistingArtifactIsIdempotentAndDifferentDocumentConflicts(@TempDir Path directory)
            throws Exception {
        AnalysisAlleleLineageSidecarPublisher publisher = publisher();
        Document first = AnalysisAlleleLineageWireFixtures.validDocument();
        Path target = directory.resolve(AnalysisAlleleLineageSidecarPublisher.FILE_NAME);

        PublicationResult created = publisher.publish(directory, first);
        byte[] accepted = Files.readAllBytes(target);
        Object fileKey = Files.readAttributes(
                target, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        PublicationResult repeated = publisher.publish(directory, first);

        assertEquals(Outcome.CREATED, created.outcome());
        assertEquals(Outcome.ALREADY_PRESENT_IDENTICAL, repeated.outcome());
        assertArrayEquals(accepted, Files.readAllBytes(target));
        assertEquals(fileKey, Files.readAttributes(
                target, java.nio.file.attribute.BasicFileAttributes.class).fileKey());

        LineageSidecarStorageException conflict = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(directory, differentDocument()));
        assertEquals(LineageSidecarStorageException.Code.TARGET_CONFLICT, conflict.code());
        assertArrayEquals(accepted, Files.readAllBytes(target));
        assertNoWriterTemporary(directory);
    }

    @Test
    void identicalExistingTargetIsForcedBeforeItsDirectoryIsSynced(@TempDir Path directory)
            throws Exception {
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();
        publisher().publish(directory, expected);
        List<String> order = new ArrayList<>();
        TestFileIdentitySource identities = new TestFileIdentitySource();
        DirectorySync sync = new RecordingDirectorySync() {
            @Override
            public synchronized void sync(Path path, Object expectedDirectoryIdentity)
                    throws IOException {
                order.add("DIRECTORY_SYNC");
                super.sync(path, expectedDirectoryIdentity);
            }
        };
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                sync,
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.TARGET_FILE_FORCED) {
                        order.add("TARGET_FILE_FORCED");
                    }
                }, identities);

        PublicationResult result = publisher.publish(directory, expected);

        assertEquals(Outcome.ALREADY_PRESENT_IDENTICAL, result.outcome());
        assertEquals(List.of("TARGET_FILE_FORCED", "DIRECTORY_SYNC"), order);
    }

    @Test
    void invalidExpectedDocumentIsAValidationFailureBeforeFilesystemAccess(
            @TempDir Path directory) throws Exception {
        Document source = AnalysisAlleleLineageWireFixtures.validDocument();
        Document invalid = new Document(null, source.inputRecords(), source.alleleRecords(),
                source.candidateLinks(), source.footer());

        LineageValidationException failure = assertThrows(
                LineageValidationException.class,
                () -> publisher().publish(directory, invalid));

        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(Files.exists(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertNoWriterTemporary(directory);
    }

    @Test
    void completeBindingRejectsDifferentBodyWithCopiedExpectedFingerprint() {
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();
        Document differentBody = differentDocument();
        Document forged = new Document(
                differentBody.header(), differentBody.inputRecords(),
                differentBody.alleleRecords(), differentBody.candidateLinks(),
                expected.footer());

        assertEquals(expected.footer().contentFingerprint(),
                forged.footer().contentFingerprint());
        assertFalse(expected.equals(forged));
        assertFalse(AnalysisAlleleLineageSidecarPublisher
                .isCompletelyBoundToExpected(forged, expected));
        assertTrue(AnalysisAlleleLineageSidecarPublisher
                .isCompletelyBoundToExpected(expected, expected));
    }

    @Test
    void invalidExistingTargetIsNeverOverwrittenOrDeleted(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve(AnalysisAlleleLineageSidecarPublisher.FILE_NAME);
        byte[] tampered = "not-a-lineage-sidecar\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, tampered);

        LineageSidecarStorageException conflict = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher().publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.TARGET_CONFLICT, conflict.code());
        assertEquals(LineageSidecarStorageException.PublicationState.NOT_PUBLISHED,
                conflict.publicationState());
        assertArrayEquals(tampered, Files.readAllBytes(target));
        assertNoWriterTemporary(directory);
    }

    @Test
    void rereadMustEqualTheCompleteExpectedDocument(@TempDir Path directory) throws Exception {
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.FILE_CLOSED) {
                        Files.write(temporary, new byte[] {'x'},
                                java.nio.file.StandardOpenOption.APPEND);
                    }
                });

        LineageSidecarStorageException rejected = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.TEMP_VERIFICATION_FAILED,
                rejected.code());
        assertFalse(Files.exists(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertNoWriterTemporary(directory);
    }

    @Test
    void rejectsRelativeNonNormalizedAndForeignProviderWorkspaces(@TempDir Path directory)
            throws Exception {
        AnalysisAlleleLineageSidecarPublisher publisher = publisher();
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();

        assertEquals(LineageSidecarStorageException.Code.WORKSPACE_INVALID,
                assertThrows(LineageSidecarStorageException.class,
                        () -> publisher.publish(Path.of("relative"), expected)).code());
        Path nonNormalized = directory.resolve("child").resolve("..");
        assertEquals(LineageSidecarStorageException.Code.WORKSPACE_INVALID,
                assertThrows(LineageSidecarStorageException.class,
                        () -> publisher.publish(nonNormalized, expected)).code());

        Path zip = directory.resolve("foreign.zip");
        URI uri = URI.create("jar:" + zip.toUri());
        try (FileSystem foreign = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path foreignWorkspace = foreign.getPath("/private");
            Files.createDirectory(foreignWorkspace);
            assertEquals(LineageSidecarStorageException.Code.WORKSPACE_INVALID,
                    assertThrows(LineageSidecarStorageException.class,
                            () -> publisher.publish(foreignWorkspace, expected)).code());
        }
    }

    @Test
    void rejectsSymbolicWorkspaceAndTargetWithoutFollowingThem(@TempDir Path directory)
            throws Exception {
        Path actualWorkspace = directory.resolve("actual");
        Path linkedWorkspace = directory.resolve("linked");
        createPrivateDirectory(actualWorkspace);
        try {
            Files.createSymbolicLink(linkedWorkspace, actualWorkspace);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("symbolic links are unavailable on this test host");
        }

        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();
        LineageSidecarStorageException workspaceFailure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher().publish(linkedWorkspace, expected));
        assertEquals(LineageSidecarStorageException.Code.INDIRECT_PATH_FORBIDDEN,
                workspaceFailure.code());

        Path outside = directory.resolve("outside");
        Files.writeString(outside, "do-not-touch");
        Path target = actualWorkspace.resolve(AnalysisAlleleLineageSidecarPublisher.FILE_NAME);
        Files.createSymbolicLink(target, outside);
        LineageSidecarStorageException targetFailure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher().publish(actualWorkspace, expected));
        assertEquals(LineageSidecarStorageException.Code.TARGET_CONFLICT,
                targetFailure.code());
        assertEquals("do-not-touch", Files.readString(outside));
    }

    @Test
    void rawIoCanaryNeverEscapesTheStableStorageException(@TempDir Path directory) {
        DirectorySync failing = new DirectorySync() {
            @Override
            public void preflight(Path ignored, Object expectedDirectoryIdentity)
                    throws IOException {
                throw new IOException(PRIVACY_CANARY);
            }

            @Override
            public void sync(Path ignored, Object expectedDirectoryIdentity) throws IOException {
                throw new IOException(PRIVACY_CANARY);
            }
        };
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                failing, AnalysisAlleleLineageSidecarPublisher.PublishFaultHook.NOOP);

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.DIRECTORY_SYNC_FAILED,
                failure.code());
        assertEquals("DIRECTORY_SYNC_FAILED:NOT_PUBLISHED", failure.getMessage());
        assertFalse(failure.toString().contains(PRIVACY_CANARY));
        assertFalse(failure.getMessage().contains(directory.toString()));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void tempCreatedHookFailureClosesChannelAndCleansOnlyTheBoundTemp(@TempDir Path directory)
            throws Exception {
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.TEMP_CREATED) {
                        throw new IOException(PRIVACY_CANARY);
                    }
                });

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.PUBLISH_FAILED, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState.NOT_PUBLISHED,
                failure.publicationState());
        assertEquals(0, failure.getSuppressed().length);
        assertNull(failure.getCause());
        assertNoWriterTemporary(directory);
        assertFalse(Files.exists(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void firstTempIdentityFailureCleansOnlyProvablyNewEmptyTemp(@TempDir Path directory)
            throws Exception {
        AtomicBoolean rejectFirstTempIdentity = new AtomicBoolean(true);
        TestFileIdentitySource identities = new TestFileIdentitySource() {
            @Override
            public Object identity(Path path, BasicFileAttributes attributes) throws IOException {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (attributes.isRegularFile()
                        && name.startsWith(".analysis-allele-lineage-v1.")
                        && name.endsWith(".writing")
                        && rejectFirstTempIdentity.compareAndSet(true, false)) {
                    throw new IOException(PRIVACY_CANARY);
                }
                return super.identity(path, attributes);
            }
        };
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                AnalysisAlleleLineageSidecarPublisher.PublishFaultHook.NOOP,
                identities);

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertFalse(rejectFirstTempIdentity.get());
        assertEquals(LineageSidecarStorageException.Code.TEMP_CREATE_FAILED, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState.NOT_PUBLISHED,
                failure.publicationState());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(PRIVACY_CANARY));
        assertNoWriterTemporary(directory);
        assertFalse(Files.exists(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void firstTempIdentityFailureRetainsTempWhenEmptyFileProofIsLost(@TempDir Path directory)
            throws Exception {
        AtomicReference<Path> retained = new AtomicReference<>();
        TestFileIdentitySource identities = new TestFileIdentitySource() {
            @Override
            public Object identity(Path path, BasicFileAttributes attributes) throws IOException {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (attributes.isRegularFile()
                        && name.startsWith(".analysis-allele-lineage-v1.")
                        && name.endsWith(".writing")
                        && retained.compareAndSet(null, path)) {
                    Files.writeString(path, "unbound-do-not-delete",
                            java.nio.file.StandardOpenOption.APPEND);
                    throw new IOException(PRIVACY_CANARY);
                }
                return super.identity(path, attributes);
            }
        };
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                AnalysisAlleleLineageSidecarPublisher.PublishFaultHook.NOOP,
                identities);

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.TEMP_CREATE_FAILED, failure.code());
        assertEquals("unbound-do-not-delete", Files.readString(retained.get()));
        assertEquals(1L, countWriterTemporary(directory));
        assertFalse(Files.exists(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void workspaceReplacementImmediatelyAfterValidationIsRejectedBeforeTempCreation(
            @TempDir Path directory) throws Exception {
        Path workspace = createPrivateDirectory(directory.resolve("workspace"));
        Path displaced = directory.resolve("displaced");
        TestFileIdentitySource identities = new TestFileIdentitySource();
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, originalWorkspace, temporary, target) -> {
                    if (stage == Stage.WORKSPACE_VALIDATED) {
                        Files.move(originalWorkspace, displaced);
                        createPrivateDirectory(originalWorkspace);
                        identities.markReplacement(originalWorkspace);
                    }
                }, identities);

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        workspace, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.WORKSPACE_INVALID, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState.NOT_PUBLISHED,
                failure.publicationState());
        assertNoWriterTemporary(workspace);
        assertNoWriterTemporary(displaced);
        assertFalse(Files.exists(workspace.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertFalse(Files.exists(displaced.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void workspaceReplacementAfterTempCreationIsRejectedBeforeWritingExpectedBytes(
            @TempDir Path directory) throws Exception {
        Path workspace = createPrivateDirectory(directory.resolve("workspace"));
        List<Stage> reached = new ArrayList<>();
        TestFileIdentitySource identities = new TestFileIdentitySource();
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, originalWorkspace, temporary, target) -> {
                    reached.add(stage);
                    if (stage == Stage.TEMP_CREATED) {
                        // Simulate the identity change caused by replacing the directory. Windows
                        // does not portably permit renaming a directory that contains this open
                        // FileChannel, so the state-machine test injects the same identity loss.
                        identities.markReplacement(originalWorkspace);
                    }
                }, identities);

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        workspace, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.WORKSPACE_INVALID, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState.NOT_PUBLISHED,
                failure.publicationState());
        assertNoWriterTemporary(workspace);
        assertFalse(Files.exists(workspace.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertFalse(reached.contains(Stage.WRITE_COMPLETED));
    }

    @Test
    void writeHookIoFailureCannotAttachRawSuppressedException(@TempDir Path directory)
            throws Exception {
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.WRITE_COMPLETED) {
                        throw new IOException(PRIVACY_CANARY);
                    }
                });

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.PUBLISH_FAILED, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState.NOT_PUBLISHED,
                failure.publicationState());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.getMessage().contains(PRIVACY_CANARY));
        assertNoWriterTemporary(directory);
    }

    @Test
    void replacementTempAfterVerificationIsNotDeletedOrPublished(@TempDir Path directory)
            throws Exception {
        AtomicReference<Path> replaced = new AtomicReference<>();
        TestFileIdentitySource identities = new TestFileIdentitySource();
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.BEFORE_PUBLISH) {
                        Files.delete(temporary);
                        Files.writeString(temporary, "replacement-do-not-delete");
                        identities.markReplacement(temporary);
                        replaced.set(temporary);
                    }
                }, identities);

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.TEMP_VERIFICATION_FAILED,
                failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState.NOT_PUBLISHED,
                failure.publicationState());
        assertEquals("replacement-do-not-delete", Files.readString(replaced.get()));
        assertFalse(Files.exists(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void targetReplacementAfterVerificationCannotBeReportedAsSuccess(@TempDir Path directory)
            throws Exception {
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.TARGET_VERIFIED) {
                        Files.delete(target);
                        Files.writeString(target, "replacement-final-do-not-delete");
                    }
                });

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.PUBLISH_FAILED, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState
                        .PRESENT_DURABILITY_UNCONFIRMED,
                failure.publicationState());
        assertEquals("replacement-final-do-not-delete", Files.readString(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertTrue(countWriterTemporary(directory) >= 1L);
    }

    @Test
    void workspaceReplacementAfterLinkKeepsVisiblePublicationState(@TempDir Path directory)
            throws Exception {
        Path workspace = createPrivateDirectory(directory.resolve("workspace"));
        Path displaced = directory.resolve("displaced");
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, originalWorkspace, temporary, target) -> {
                    if (stage == Stage.TARGET_VERIFIED) {
                        Files.move(originalWorkspace, displaced);
                        createPrivateDirectory(originalWorkspace);
                    }
                });

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        workspace, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.WORKSPACE_INVALID, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState
                        .PRESENT_DURABILITY_UNCONFIRMED,
                failure.publicationState());
        assertTrue(Files.isRegularFile(displaced.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertFalse(Files.exists(workspace.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void workspaceReplacementAfterDirectorySyncKeepsDurableState(@TempDir Path directory)
            throws Exception {
        Path workspace = createPrivateDirectory(directory.resolve("workspace"));
        Path displaced = directory.resolve("displaced");
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, originalWorkspace, temporary, target) -> {
                    if (stage == Stage.DIRECTORY_SYNCED) {
                        Files.move(originalWorkspace, displaced);
                        createPrivateDirectory(originalWorkspace);
                    }
                });

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        workspace, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.WORKSPACE_INVALID, failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState.PUBLISHED_DURABLE,
                failure.publicationState());
        assertTrue(Files.isRegularFile(displaced.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void directorySyncFailureAfterLinkNeverDeletesCompleteFinal(@TempDir Path directory)
            throws Exception {
        DirectorySync failsAfterLink = new DirectorySync() {
            @Override
            public void preflight(Path ignored, Object expectedDirectoryIdentity) {
            }

            @Override
            public void sync(Path ignored, Object expectedDirectoryIdentity) throws IOException {
                throw new IOException(PRIVACY_CANARY);
            }
        };
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                failsAfterLink, AnalysisAlleleLineageSidecarPublisher.PublishFaultHook.NOOP);
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(directory, expected));

        assertEquals(LineageSidecarStorageException.Code.DIRECTORY_SYNC_FAILED,
                failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState
                        .PRESENT_DURABILITY_UNCONFIRMED,
                failure.publicationState());
        Path target = directory.resolve(AnalysisAlleleLineageSidecarPublisher.FILE_NAME);
        assertTrue(Files.isRegularFile(target));
        assertTrue(countWriterTemporary(directory) >= 1L);
        assertEquals(expected, readWithCodec(target));
        assertFalse(failure.toString().contains(PRIVACY_CANARY));
    }

    @Test
    void workspaceReplacementInsideDirectorySyncCannotBeReportedDurable(
            @TempDir Path directory) throws Exception {
        Path workspace = createPrivateDirectory(directory.resolve("workspace"));
        Path displaced = directory.resolve("displaced");
        TestFileIdentitySource identities = new TestFileIdentitySource();
        DirectorySync replacingSync = new DirectorySync() {
            @Override
            public void preflight(Path path, Object expectedDirectoryIdentity)
                    throws IOException {
                requireTestDirectoryIdentity(
                        identities, path, expectedDirectoryIdentity);
            }

            @Override
            public void sync(Path path, Object expectedDirectoryIdentity) throws IOException {
                Files.move(path, displaced);
                createPrivateDirectory(path);
                identities.markReplacement(path);
                requireTestDirectoryIdentity(
                        identities, path, expectedDirectoryIdentity);
            }
        };
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                replacingSync,
                AnalysisAlleleLineageSidecarPublisher.PublishFaultHook.NOOP,
                identities);

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        workspace, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.DIRECTORY_SYNC_FAILED,
                failure.code());
        assertEquals(LineageSidecarStorageException.PublicationState
                        .PRESENT_DURABILITY_UNCONFIRMED,
                failure.publicationState());
        assertTrue(Files.isRegularFile(displaced.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertFalse(Files.exists(workspace.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void faultStagesReportTheHighestPersistenceMilestoneReached(@TempDir Path directory)
            throws Exception {
        assertFaultStage(directory, Stage.TEMP_CREATED,
                LineageSidecarStorageException.PublicationState.NOT_PUBLISHED);
        assertFaultStage(directory, Stage.WRITE_COMPLETED,
                LineageSidecarStorageException.PublicationState.NOT_PUBLISHED);
        assertFaultStage(directory, Stage.TARGET_LINKED,
                LineageSidecarStorageException.PublicationState
                        .PRESENT_DURABILITY_UNCONFIRMED);
        assertFaultStage(directory, Stage.DIRECTORY_SYNCED,
                LineageSidecarStorageException.PublicationState.PUBLISHED_DURABLE);
        assertFaultStage(directory, Stage.TEMP_DELETED,
                LineageSidecarStorageException.PublicationState.PUBLISHED_DURABLE);

        Path existingWorkspace = createPrivateDirectory(
                directory.resolve("target-file-forced"));
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();
        publisher().publish(existingWorkspace, expected);
        AnalysisAlleleLineageSidecarPublisher existingPublisher = publisher(
                new RecordingDirectorySync(),
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.TARGET_FILE_FORCED) {
                        throw new IOException(PRIVACY_CANARY);
                    }
                });

        LineageSidecarStorageException targetForceFailure = assertThrows(
                LineageSidecarStorageException.class,
                () -> existingPublisher.publish(existingWorkspace, expected));
        assertSanitizedState(targetForceFailure,
                LineageSidecarStorageException.PublicationState
                        .PRESENT_DURABILITY_UNCONFIRMED);
    }

    @Test
    void cleanupDirectorySyncedHookFailureReturnsUnconfirmedDeletionDurability(
            @TempDir Path directory) throws Exception {
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, workspace, temporary, target) -> {
                    if (stage == Stage.CLEANUP_DIRECTORY_SYNCED) {
                        throw new IOException(PRIVACY_CANARY);
                    }
                });

        PublicationResult result = publisher.publish(
                directory, AnalysisAlleleLineageWireFixtures.validDocument());

        assertEquals(Outcome.CREATED, result.outcome());
        assertEquals(CleanupStatus.DELETION_DURABILITY_UNCONFIRMED,
                result.cleanupStatus());
        assertNoWriterTemporary(directory);
        assertTrue(Files.isRegularFile(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
    }

    @Test
    void concurrentIdenticalWritersAreIdempotent(@TempDir Path directory) throws Exception {
        BeforePublishBarrier barrier = new BeforePublishBarrier(4);
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(), barrier);
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<PublicationResult>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(executor.submit(() -> publisher.publish(directory, expected)));
            }
            assertTrue(barrier.awaitAllWriters());
            barrier.release();
            int created = 0;
            int identical = 0;
            for (Future<PublicationResult> future : futures) {
                if (future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).outcome()
                        == Outcome.CREATED) created++;
                else identical++;
            }
            assertEquals(1, created);
            assertEquals(3, identical);
            assertEquals(3, barrier.concurrentPublicationCount());
            assertEquals(3, barrier.targetForceCount());
            assertNoWriterTemporary(directory);
            assertEquals(expected, publisher.verifyExpected(directory, expected).document());
        } finally {
            barrier.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDetectionHookFailureReportsVisibleUnconfirmedState(
            @TempDir Path directory) throws Exception {
        BeforePublishBarrier barrier = new BeforePublishBarrier(2, true);
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(), barrier);
        Document expected = AnalysisAlleleLineageWireFixtures.validDocument();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(
                    () -> publishOrFailure(publisher, directory, expected));
            Future<Object> second = executor.submit(
                    () -> publishOrFailure(publisher, directory, expected));
            assertTrue(barrier.awaitAllWriters());
            barrier.release();

            Object a = first.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Object b = second.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(a == Outcome.CREATED || b == Outcome.CREATED);
            Object failed = a instanceof LineageSidecarStorageException ? a : b;
            assertTrue(failed instanceof LineageSidecarStorageException);
            LineageSidecarStorageException failure =
                    (LineageSidecarStorageException) failed;
            assertEquals(LineageSidecarStorageException.Code.PUBLISH_FAILED, failure.code());
            assertSanitizedState(failure, LineageSidecarStorageException.PublicationState
                    .PRESENT_DURABILITY_UNCONFIRMED);
            assertEquals(1, barrier.concurrentPublicationCount());
            assertNoWriterTemporary(directory);
            assertEquals(expected, publisher.verifyExpected(directory, expected).document());
        } finally {
            barrier.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDifferentWritersNeverOverwriteWinner(@TempDir Path directory) throws Exception {
        BeforePublishBarrier barrier = new BeforePublishBarrier(2);
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(), barrier);
        Document first = AnalysisAlleleLineageWireFixtures.validDocument();
        Document second = differentDocument();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> firstResult = executor.submit(() -> publishOrCode(
                    publisher, directory, first));
            Future<Object> secondResult = executor.submit(() -> publishOrCode(
                    publisher, directory, second));
            assertTrue(barrier.awaitAllWriters());
            barrier.release();
            Object a = firstResult.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Object b = secondResult.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(a == Outcome.CREATED || b == Outcome.CREATED);
            assertTrue(a == LineageSidecarStorageException.Code.TARGET_CONFLICT
                    || b == LineageSidecarStorageException.Code.TARGET_CONFLICT);
            assertEquals(1, barrier.concurrentPublicationCount());

            Path target = directory.resolve(AnalysisAlleleLineageSidecarPublisher.FILE_NAME);
            Document winner = readWithCodec(target);
            assertTrue(winner.equals(first) || winner.equals(second));
            assertNoWriterTemporary(directory);
        } finally {
            barrier.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void posixTemporaryAndFinalRemainOwnerOnly(@TempDir Path directory) throws Exception {
        PosixFileAttributeView view = Files.getFileAttributeView(
                directory, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX permissions unavailable");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));

        publisher().publish(directory, AnalysisAlleleLineageWireFixtures.validDocument());

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                directory.resolve(AnalysisAlleleLineageSidecarPublisher.FILE_NAME));
        assertEquals(EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE), permissions);
    }

    @Test
    void defaultWindowsDirectorySyncFailsClosedBeforeCreatingAnyArtifact(@TempDir Path directory) {
        Assumptions.assumeTrue(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win"));

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> new AnalysisAlleleLineageSidecarPublisher().publish(
                        directory, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.DIRECTORY_SYNC_UNSUPPORTED,
                failure.code());
        assertFalse(Files.exists(directory.resolve(
                AnalysisAlleleLineageSidecarPublisher.FILE_NAME)));
        assertEquals(0L, countWriterTemporary(directory));
    }

    private static void assertFaultStage(
            Path parent,
            Stage faultStage,
            LineageSidecarStorageException.PublicationState expectedState) throws Exception {
        Path workspace = createPrivateDirectory(parent.resolve(faultStage.name().toLowerCase(
                java.util.Locale.ROOT)));
        AnalysisAlleleLineageSidecarPublisher publisher = publisher(
                new RecordingDirectorySync(),
                (stage, ignoredWorkspace, temporary, target) -> {
                    if (stage == faultStage) {
                        throw new IOException(PRIVACY_CANARY);
                    }
                });

        LineageSidecarStorageException failure = assertThrows(
                LineageSidecarStorageException.class,
                () -> publisher.publish(
                        workspace, AnalysisAlleleLineageWireFixtures.validDocument()));

        assertEquals(LineageSidecarStorageException.Code.PUBLISH_FAILED, failure.code());
        assertSanitizedState(failure, expectedState);
    }

    private static void assertSanitizedState(
            LineageSidecarStorageException failure,
            LineageSidecarStorageException.PublicationState expectedState) {
        assertEquals(expectedState, failure.publicationState());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(PRIVACY_CANARY));
        assertFalse(failure.getMessage().contains(PRIVACY_CANARY));
    }

    private static Object publishOrCode(AnalysisAlleleLineageSidecarPublisher publisher,
                                        Path directory,
                                        Document expected) {
        try {
            return publisher.publish(directory, expected).outcome();
        } catch (LineageSidecarStorageException exception) {
            return exception.code();
        }
    }

    private static Object publishOrFailure(AnalysisAlleleLineageSidecarPublisher publisher,
                                           Path directory,
                                           Document expected) {
        try {
            return publisher.publish(directory, expected).outcome();
        } catch (LineageSidecarStorageException exception) {
            return exception;
        }
    }

    private static AnalysisAlleleLineageSidecarPublisher publisher() {
        return publisher(new RecordingDirectorySync(),
                AnalysisAlleleLineageSidecarPublisher.PublishFaultHook.NOOP);
    }

    private static AnalysisAlleleLineageSidecarPublisher publisher(
            DirectorySync sync,
            AnalysisAlleleLineageSidecarPublisher.PublishFaultHook hook) {
        return publisher(sync, hook, new TestFileIdentitySource());
    }

    private static AnalysisAlleleLineageSidecarPublisher publisher(
            DirectorySync sync,
            AnalysisAlleleLineageSidecarPublisher.PublishFaultHook hook,
            TestFileIdentitySource identitySource) {
        if (sync instanceof RecordingDirectorySync) {
            ((RecordingDirectorySync) sync).bind(identitySource);
        }
        return new AnalysisAlleleLineageSidecarPublisher(
                new AnalysisAlleleLineageNdjsonCodec(), sync, hook,
                new SecureRandom(), identitySource);
    }

    private static Document differentDocument() {
        Document source = AnalysisAlleleLineageWireFixtures.validDocument();
        AlleleRecord original = source.alleleRecords().get(0);
        AlleleKey key = original.sourceKey();
        AlleleRecord changed = AnalysisAlleleLineageWireFixtures.matchedAllele(
                original.recordOrdinal(), original.sourceRecordOrdinal(),
                original.sourceAlternateIndex(), original.sourceAlternateCount(),
                "abcdef0123456789abcdef01", key,
                original.engineAlleles().get(0).engineAlleleId());
        return AnalysisAlleleLineageWireFixtures.reseal(new Document(
                source.header(), source.inputRecords(), List.of(changed),
                source.candidateLinks(), source.footer()));
    }

    private static Document readWithCodec(Path target) throws Exception {
        try (java.io.InputStream input = Files.newInputStream(target)) {
            return new AnalysisAlleleLineageNdjsonCodec().read(input);
        }
    }

    private static Path createPrivateDirectory(Path path) throws IOException {
        Files.createDirectory(path);
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
        return path;
    }

    private static void assertNoWriterTemporary(Path directory) throws Exception {
        assertEquals(0L, countWriterTemporary(directory));
    }

    private static long countWriterTemporary(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(
                            ".analysis-allele-lineage-v1."))
                    .filter(path -> path.getFileName().toString().endsWith(".writing"))
                    .count();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void requireTestDirectoryIdentity(TestFileIdentitySource identities,
                                                     Path directory,
                                                     Object expectedDirectoryIdentity)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Object actual = identities.identity(directory, attributes);
        if (expectedDirectoryIdentity == null || !expectedDirectoryIdentity.equals(actual)) {
            throw new IOException();
        }
    }

    private static final class BeforePublishBarrier
            implements AnalysisAlleleLineageSidecarPublisher.PublishFaultHook {
        private final CountDownLatch arrived;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger concurrentPublications = new AtomicInteger();
        private final AtomicInteger targetForces = new AtomicInteger();
        private final boolean failOnConcurrentPublication;

        private BeforePublishBarrier(int writers) {
            this(writers, false);
        }

        private BeforePublishBarrier(int writers, boolean failOnConcurrentPublication) {
            arrived = new CountDownLatch(writers);
            this.failOnConcurrentPublication = failOnConcurrentPublication;
        }

        @Override
        public void reached(Stage stage, Path workspace, Path temporary, Path target)
                throws IOException {
            if (stage == Stage.BEFORE_PUBLISH) {
                arrived.countDown();
                try {
                    if (!release.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException();
                }
            } else if (stage == Stage.CONCURRENT_PUBLICATION_DETECTED) {
                concurrentPublications.incrementAndGet();
                if (failOnConcurrentPublication) {
                    throw new IOException(PRIVACY_CANARY);
                }
            } else if (stage == Stage.TARGET_FILE_FORCED) {
                targetForces.incrementAndGet();
            }
        }

        private boolean awaitAllWriters() throws InterruptedException {
            return arrived.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private int concurrentPublicationCount() {
            return concurrentPublications.get();
        }

        private int targetForceCount() {
            return targetForces.get();
        }
    }

    private static class RecordingDirectorySync implements DirectorySync {
        private int syncCount;
        private TestFileIdentitySource identitySource;

        private void bind(TestFileIdentitySource value) {
            identitySource = value;
        }

        @Override
        public void preflight(Path directory, Object expectedDirectoryIdentity)
                throws IOException {
            requireIdentity(directory, expectedDirectoryIdentity);
        }

        @Override
        public synchronized void sync(Path directory, Object expectedDirectoryIdentity)
                throws IOException {
            requireIdentity(directory, expectedDirectoryIdentity);
            syncCount++;
        }

        private void requireIdentity(Path directory, Object expectedDirectoryIdentity)
                throws IOException {
            if (identitySource == null) {
                throw new IOException();
            }
            requireTestDirectoryIdentity(
                    identitySource, directory, expectedDirectoryIdentity);
        }
    }

    /**
     * Java's Windows provider may expose a null BasicFileAttributes.fileKey(). Production fails
     * closed in that case. These synthetic-only tests inject a timestamp fallback so the
     * publication state machine can be exercised without weakening the production constructor.
     */
    private static class TestFileIdentitySource implements FileIdentitySource {
        private final Map<Path, AtomicInteger> generations = new ConcurrentHashMap<>();

        @Override
        public Object identity(Path path, BasicFileAttributes attributes) throws IOException {
            Path key = path.toAbsolutePath().normalize();
            int generation = generations.computeIfAbsent(
                    key, ignored -> new AtomicInteger()).get();
            Object providerKey = attributes.fileKey();
            return providerKey == null
                    ? new TestIdentity(
                    null, attributes.isDirectory(), attributes.creationTime(), generation)
                    : new TestIdentity(providerKey, false, null, generation);
        }

        @Override
        public boolean authorizesDeletion() {
            return true;
        }

        private void markReplacement(Path path) {
            generations.computeIfAbsent(path.toAbsolutePath().normalize(),
                    ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static final class TestIdentity {
        private final Object providerKey;
        private final boolean directory;
        private final FileTime created;
        private final int generation;

        private TestIdentity(Object providerKey,
                             boolean directory,
                             FileTime created,
                             int generation) {
            this.providerKey = providerKey;
            this.directory = directory;
            this.created = created;
            this.generation = generation;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TestIdentity)) {
                return false;
            }
            TestIdentity that = (TestIdentity) other;
            return Objects.equals(providerKey, that.providerKey)
                    && directory == that.directory
                    && Objects.equals(created, that.created)
                    && generation == that.generation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(providerKey, directory, created, generation);
        }
    }
}
