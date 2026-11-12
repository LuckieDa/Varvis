package org.nbscn.varvis.variantanalysis.lineage;

import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.Channels;
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
import java.security.DigestInputStream;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.nbscn.varvis.variantanalysis.lineage.LineageSidecarStorageException.Code;
import static org.nbscn.varvis.variantanalysis.lineage.LineageSidecarStorageException.PublicationState;

/**
 * Synthetic-only, non-overwriting publisher for a bounded private lineage sidecar.
 *
 * <p>This Phase B2a class deliberately inherits the B1 limits: at most 10,000 body records and
 * 16 MiB of canonical NDJSON. It is package-private and is not connected to product runtime,
 * persistence, hydration, or reuse. A full product-scale publisher requires a separate bounded
 * streaming verifier.</p>
 *
 * <p>The caller supplies one already-created, private workspace. The final file name is fixed and
 * never contains a job, sample, patient, upload, host, or account identifier.</p>
 *
 * <p>Phase B2a assumes that this owner-only workspace is exclusively controlled by the current
 * service UID. Java 17 exposes neither {@code openat(2)}-style handle-relative traversal nor a
 * conditional unlink, so identity is checked at every observable boundary but an actively
 * malicious same-UID mutation inside an individual check/use window is outside this synthetic
 * phase. Product integration requires native or handle-based hardening.</p>
 */
final class AnalysisAlleleLineageSidecarPublisher {

    static final String FILE_NAME = "analysis-allele-lineage-v1.ndjson";
    private static final String TEMP_PREFIX = ".analysis-allele-lineage-v1.";
    private static final String TEMP_SUFFIX = ".writing";
    private static final Pattern TEMP_NAME = Pattern.compile(
            "^\\.analysis-allele-lineage-v1\\.[0-9a-f]{32}\\.writing$");
    private static final int MAX_TEMP_NAME_ATTEMPTS = 16;

    private final AnalysisAlleleLineageNdjsonCodec codec;
    private final DirectorySync directorySync;
    private final PublishFaultHook faultHook;
    private final SecureRandom random;
    private final FileIdentitySource identitySource;

    AnalysisAlleleLineageSidecarPublisher() {
        this(new AnalysisAlleleLineageNdjsonCodec(), platformDirectorySync(),
                PublishFaultHook.NOOP, new SecureRandom(), FileIdentitySource.STRICT);
    }

    AnalysisAlleleLineageSidecarPublisher(DirectorySync directorySync,
                                           PublishFaultHook faultHook) {
        this(new AnalysisAlleleLineageNdjsonCodec(), directorySync, faultHook,
                new SecureRandom(), FileIdentitySource.STRICT);
    }

    AnalysisAlleleLineageSidecarPublisher(AnalysisAlleleLineageNdjsonCodec codec,
                                           DirectorySync directorySync,
                                           PublishFaultHook faultHook,
                                           SecureRandom random) {
        this(codec, directorySync, faultHook, random, FileIdentitySource.STRICT);
    }

    AnalysisAlleleLineageSidecarPublisher(AnalysisAlleleLineageNdjsonCodec codec,
                                           DirectorySync directorySync,
                                           PublishFaultHook faultHook,
                                           SecureRandom random,
                                           FileIdentitySource identitySource) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.directorySync = Objects.requireNonNull(directorySync, "directorySync");
        this.faultHook = Objects.requireNonNull(faultHook, "faultHook");
        this.random = Objects.requireNonNull(random, "random");
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
    }

    /**
     * Publishes one already validated logical document.
     *
     * @throws LineageValidationException when {@code expected} violates the wire contract; this
     *         caller error is raised before any filesystem access
     * @throws LineageSidecarStorageException for a sanitized storage-boundary failure
     */
    PublicationResult publish(Path privateWorkspace, Document expected)
            throws LineageSidecarStorageException {
        validateExpected(expected);
        Workspace workspace = validateWorkspace(privateWorkspace);
        requireDirectorySync(workspace);
        requireWorkspaceIdentity(workspace, PublicationState.NOT_PUBLISHED);
        reach(Stage.WORKSPACE_VALIDATED, workspace.path, null, workspace.target,
                PublicationState.NOT_PUBLISHED);
        revalidateWorkspace(workspace, PublicationState.NOT_PUBLISHED);

        if (existsNoFollow(workspace.target)) {
            VerifiedSidecar existing = verifyFile(
                    workspace.target, expected, VerificationRole.TARGET);
            existing = forceAndReverifyTarget(
                    workspace, expected, existing,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            syncDirectory(workspace, PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            revalidateWorkspace(workspace, PublicationState.PUBLISHED_DURABLE);
            VerifiedSidecar durable = verifyTargetAtState(
                    workspace.target, expected, PublicationState.PUBLISHED_DURABLE);
            requireVerifiedSnapshot(existing, durable, Code.TARGET_CONFLICT,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            return new PublicationResult(
                    Outcome.ALREADY_PRESENT_IDENTICAL, durable, CleanupStatus.CLEAN);
        }

        Path temporary = null;
        FileSnapshot temporaryIdentity = null;
        TempArtifact openArtifact = null;
        boolean targetLinked = false;
        boolean targetDurable = false;
        try {
            TempArtifact artifact = createTemporary(workspace);
            openArtifact = artifact;
            temporary = artifact.path;
            temporaryIdentity = artifact.identity;
            reach(Stage.TEMP_CREATED, workspace.path, temporary, workspace.target,
                    PublicationState.NOT_PUBLISHED);
            revalidateWorkspace(workspace, PublicationState.NOT_PUBLISHED);
            requireUnchangedFile(temporary, temporaryIdentity,
                    Code.TEMP_VERIFICATION_FAILED, PublicationState.NOT_PUBLISHED);

            writeAndForce(artifact, expected, workspace);
            reach(Stage.FILE_CLOSED, workspace.path, temporary, workspace.target,
                    PublicationState.NOT_PUBLISHED);

            VerifiedSidecar verifiedTemporary = verifyFile(
                    temporary, expected, VerificationRole.TEMPORARY);
            temporaryIdentity = verifiedTemporary.snapshot;
            reach(Stage.TEMP_VERIFIED, workspace.path, temporary, workspace.target,
                    PublicationState.NOT_PUBLISHED);

            revalidateWorkspace(workspace, PublicationState.NOT_PUBLISHED);
            reach(Stage.BEFORE_PUBLISH, workspace.path, temporary, workspace.target,
                    PublicationState.NOT_PUBLISHED);
            revalidateWorkspace(workspace, PublicationState.NOT_PUBLISHED);
            requireUnchangedFile(temporary, temporaryIdentity,
                    Code.TEMP_VERIFICATION_FAILED, PublicationState.NOT_PUBLISHED);

            try {
                Files.createLink(workspace.target, temporary);
                targetLinked = true;
            } catch (FileAlreadyExistsException exception) {
                return finishConcurrentPublication(
                        workspace, temporary, temporaryIdentity, expected);
            } catch (UnsupportedOperationException exception) {
                throw failure(Code.ATOMIC_PUBLISH_UNSUPPORTED,
                        PublicationState.NOT_PUBLISHED);
            } catch (IOException | SecurityException exception) {
                if (existsNoFollow(workspace.target)) {
                    return finishConcurrentPublication(
                            workspace, temporary, temporaryIdentity, expected);
                }
                throw failure(Code.PUBLISH_FAILED, PublicationState.NOT_PUBLISHED);
            }

            reach(Stage.TARGET_LINKED, workspace.path, temporary, workspace.target,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            requireUnchangedFile(temporary, temporaryIdentity,
                    Code.TEMP_VERIFICATION_FAILED,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            requireSameFile(temporary, workspace.target,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            VerifiedSidecar verifiedTarget;
            try {
                verifiedTarget = verifyFile(
                        workspace.target, expected, VerificationRole.TARGET);
            } catch (LineageSidecarStorageException exception) {
                throw failure(exception.code(),
                        PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            }
            requireVerifiedSnapshot(verifiedTemporary, verifiedTarget,
                    Code.EXPECTED_DOCUMENT_MISMATCH,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            reach(Stage.TARGET_VERIFIED, workspace.path, temporary, workspace.target,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);

            revalidateWorkspace(
                    workspace, PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            requireSameFile(temporary, workspace.target,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            verifiedTarget = verifyTargetAtState(
                    workspace.target, expected,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            requireVerifiedSnapshot(verifiedTemporary, verifiedTarget,
                    Code.EXPECTED_DOCUMENT_MISMATCH,
                    PublicationState.PRESENT_DURABILITY_UNCONFIRMED);

            syncDirectory(workspace, PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
            targetDurable = true;
            reach(Stage.DIRECTORY_SYNCED, workspace.path, temporary, workspace.target,
                    PublicationState.PUBLISHED_DURABLE);

            revalidateWorkspace(workspace, PublicationState.PUBLISHED_DURABLE);
            requireSameFile(temporary, workspace.target,
                    PublicationState.PUBLISHED_DURABLE);
            verifiedTarget = verifyTargetAtState(
                    workspace.target, expected, PublicationState.PUBLISHED_DURABLE);
            requireVerifiedSnapshot(verifiedTemporary, verifiedTarget,
                    Code.EXPECTED_DOCUMENT_MISMATCH,
                    PublicationState.PUBLISHED_DURABLE);

            CleanupStatus cleanup = cleanupPublishedTemporary(
                    workspace, temporary, temporaryIdentity);
            revalidateWorkspace(workspace, PublicationState.PUBLISHED_DURABLE);
            VerifiedSidecar finalVerified = verifyTargetAtState(
                    workspace.target, expected, PublicationState.PUBLISHED_DURABLE);
            requireVerifiedSnapshot(verifiedTemporary, finalVerified,
                    Code.EXPECTED_DOCUMENT_MISMATCH,
                    PublicationState.PUBLISHED_DURABLE);
            return new PublicationResult(Outcome.CREATED, finalVerified, cleanup);
        } finally {
            if (openArtifact != null) {
                closeQuietly(openArtifact.channel);
            }
            if (!targetLinked && temporary != null) {
                cleanupOwnTemporary(temporary, temporaryIdentity);
            } else if (targetLinked && !targetDurable) {
                // A complete final name may already be visible. Keep the same-inode private temp
                // as crash evidence and never delete or roll back the final name here.
            }
        }
    }

    /**
     * Verifies and durability-binds an existing target to the complete expected document.
     *
     * @throws LineageValidationException when {@code expected} violates the wire contract, before
     *         any filesystem access
     * @throws LineageSidecarStorageException for a sanitized storage-boundary failure
     */
    VerifiedSidecar verifyExpected(Path privateWorkspace, Document expected)
            throws LineageSidecarStorageException {
        validateExpected(expected);
        Workspace workspace = validateWorkspace(privateWorkspace);
        requireDirectorySync(workspace);
        requireWorkspaceIdentity(workspace, PublicationState.NOT_PUBLISHED);
        if (!existsNoFollow(workspace.target)) {
            throw failure(Code.TARGET_MISSING, PublicationState.NOT_PUBLISHED);
        }
        VerifiedSidecar verified = verifyFile(
                workspace.target, expected, VerificationRole.TARGET);
        verified = forceAndReverifyTarget(
                workspace, expected, verified,
                PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
        syncDirectory(workspace, PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
        revalidateWorkspace(workspace, PublicationState.PUBLISHED_DURABLE);
        VerifiedSidecar durable = verifyTargetAtState(
                workspace.target, expected, PublicationState.PUBLISHED_DURABLE);
        requireVerifiedSnapshot(verified, durable, Code.TARGET_CONFLICT,
                PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
        return durable;
    }

    private void validateExpected(Document expected) throws LineageSidecarStorageException {
        try {
            codec.write(expected, OutputStream.nullOutputStream());
        } catch (LineageValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.WRITE_FAILED, PublicationState.NOT_PUBLISHED);
        }
    }

    private Workspace validateWorkspace(Path supplied) throws LineageSidecarStorageException {
        if (supplied == null) {
            throw failure(Code.WORKSPACE_REQUIRED, PublicationState.NOT_PUBLISHED);
        }
        try {
            if (supplied.getFileSystem().provider()
                    != FileSystems.getDefault().provider()
                    || !"file".equalsIgnoreCase(
                    supplied.getFileSystem().provider().getScheme())
                    || !supplied.isAbsolute()
                    || !supplied.equals(supplied.normalize())) {
                throw failure(Code.WORKSPACE_INVALID, PublicationState.NOT_PUBLISHED);
            }
            URI uri = supplied.toUri();
            if (uri.getAuthority() != null || isNetworkOrDevicePath(supplied.toString())) {
                throw failure(Code.FILESYSTEM_UNSUPPORTED, PublicationState.NOT_PUBLISHED);
            }

            Path root = supplied.getRoot();
            if (root == null) {
                throw failure(Code.WORKSPACE_INVALID, PublicationState.NOT_PUBLISHED);
            }
            requireDirectoryComponent(root);
            Path current = root;
            for (Path part : supplied) {
                current = current.resolve(part);
                requireDirectoryComponent(current);
            }
            requirePrivateWorkspace(supplied);
            Path real = supplied.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.isAbsolute() || !real.equals(real.normalize())) {
                throw failure(Code.WORKSPACE_INVALID, PublicationState.NOT_PUBLISHED);
            }
            Path target = real.resolve(FILE_NAME).normalize();
            if (target.getParent() == null || !target.getParent().equals(real)) {
                throw failure(Code.WORKSPACE_INVALID, PublicationState.NOT_PUBLISHED);
            }
            BasicFileAttributes attributes = readBasic(real);
            return new Workspace(real, target, identitySource.identity(real, attributes));
        } catch (LineageSidecarStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.WORKSPACE_INVALID, PublicationState.NOT_PUBLISHED);
        }
    }

    private void revalidateWorkspace(Workspace workspace, PublicationState state)
            throws LineageSidecarStorageException {
        Workspace current;
        try {
            current = validateWorkspace(workspace.path);
        } catch (LineageSidecarStorageException exception) {
            throw failure(exception.code(), state);
        }
        if (workspace.identity == null || current.identity == null
                || !workspace.identity.equals(current.identity)) {
            throw failure(Code.WORKSPACE_INVALID, state);
        }
        if (!workspace.path.equals(current.path)
                || !workspace.target.equals(current.target)) {
            throw failure(Code.WORKSPACE_INVALID, state);
        }
    }

    private static void requireWorkspaceIdentity(Workspace workspace,
                                                 PublicationState state)
            throws LineageSidecarStorageException {
        if (workspace.identity == null) {
            throw failure(Code.FILESYSTEM_UNSUPPORTED, state);
        }
    }

    private void requireDirectoryComponent(Path path)
            throws LineageSidecarStorageException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(path)) {
                throw failure(Code.INDIRECT_PATH_FORBIDDEN,
                        PublicationState.NOT_PUBLISHED);
            }
        } catch (LineageSidecarStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.WORKSPACE_INVALID, PublicationState.NOT_PUBLISHED);
        }
    }

    private void requirePrivateWorkspace(Path workspace)
            throws LineageSidecarStorageException {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(
                    workspace, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                // Windows B2a is testable through an injected DirectorySync, but the default
                // publisher fails closed before publication because Java 17 cannot portably
                // prove directory-entry durability or effective DACL privacy.
                return;
            }
            PosixFileAttributes attributes = view.readAttributes();
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || !permissions.contains(PosixFilePermission.OWNER_WRITE)
                    || !permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.stream().anyMatch(value -> value.name().startsWith("GROUP_")
                    || value.name().startsWith("OTHERS_"))) {
                throw failure(Code.WORKSPACE_NOT_PRIVATE,
                        PublicationState.NOT_PUBLISHED);
            }
        } catch (LineageSidecarStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.WORKSPACE_NOT_PRIVATE,
                    PublicationState.NOT_PUBLISHED);
        }
    }

    private TempArtifact createTemporary(Workspace workspace)
            throws LineageSidecarStorageException {
        for (int attempt = 0; attempt < MAX_TEMP_NAME_ATTEMPTS; attempt++) {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            String name = TEMP_PREFIX + HexFormat.of().formatHex(randomBytes) + TEMP_SUFFIX;
            Path path = workspace.path.resolve(name).normalize();
            if (path.getParent() == null || !path.getParent().equals(workspace.path)
                    || !TEMP_NAME.matcher(path.getFileName().toString()).matches()) {
                throw failure(Code.TEMP_CREATE_FAILED, PublicationState.NOT_PUBLISHED);
            }
            FileChannel channel = null;
            try {
                channel = openNewPrivateFile(path, workspace.path);
                FileSnapshot identity = snapshotRegularFile(
                        path, Code.TEMP_CREATE_FAILED, PublicationState.NOT_PUBLISHED);
                return new TempArtifact(path, channel, identity);
            } catch (FileAlreadyExistsException exception) {
                // A random collision is harmless. Never inspect or replace the existing entry.
            } catch (LineageSidecarStorageException exception) {
                boolean createSucceeded = channel != null;
                closeQuietly(channel);
                if (createSucceeded) {
                    cleanupNewEmptyTemporaryAfterSnapshotFailure(workspace, path);
                }
                throw exception;
            } catch (IOException | RuntimeException exception) {
                boolean createSucceeded = channel != null;
                closeQuietly(channel);
                if (createSucceeded) {
                    cleanupNewEmptyTemporaryAfterSnapshotFailure(workspace, path);
                }
                throw failure(Code.TEMP_CREATE_FAILED, PublicationState.NOT_PUBLISHED);
            }
        }
        throw failure(Code.TEMP_CREATE_FAILED, PublicationState.NOT_PUBLISHED);
    }

    private void cleanupNewEmptyTemporaryAfterSnapshotFailure(Workspace workspace, Path path) {
        // CREATE_NEW proved that this publisher created the entry, but the failed first identity
        // snapshot cannot authorize normal identity-based cleanup. Within B2a's documented
        // owner-only/exclusive workspace boundary, delete only when every observable safety fact
        // still identifies a newly-created, empty writer temp. Any doubt leaves it in place.
        try {
            revalidateWorkspace(workspace, PublicationState.NOT_PUBLISHED);
            if (path == null || path.getParent() == null
                    || !path.getParent().equals(workspace.path)
                    || path.getFileName() == null
                    || !TEMP_NAME.matcher(path.getFileName().toString()).matches()) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || attributes.size() != 0L
                    || isReparsePoint(path)) {
                return;
            }
            Files.delete(path);
        } catch (IOException | RuntimeException ignored) {
            // This is deliberately best-effort. Without a bound identity, retaining a stale,
            // opaque random temp is safer than deleting an entry that can no longer be proved.
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The public error remains the stable create failure code.
        }
    }

    private FileChannel openNewPrivateFile(Path path, Path workspace) throws IOException {
        Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributeView posix = Files.getFileAttributeView(
                workspace, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null) {
            return FileChannel.open(path, options);
        }
        FileAttribute<Set<PosixFilePermission>> mode = PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return FileChannel.open(path, options, mode);
    }

    private void writeAndForce(TempArtifact artifact,
                               Document expected,
                               Workspace workspace)
            throws LineageSidecarStorageException {
        LineageValidationException validationFailure = null;
        Code storageFailure = null;
        FileChannel channel = artifact.channel;
        try {
            try {
                codec.write(expected, Channels.newOutputStream(channel));
                reach(Stage.WRITE_COMPLETED, workspace.path, artifact.path, workspace.target,
                        PublicationState.NOT_PUBLISHED);
            } catch (LineageValidationException exception) {
                validationFailure = exception;
            } catch (LineageSidecarStorageException exception) {
                storageFailure = exception.code();
            } catch (IOException | RuntimeException exception) {
                storageFailure = Code.WRITE_FAILED;
            }

            if (validationFailure == null && storageFailure == null) {
                try {
                channel.force(true);
                reach(Stage.FILE_FORCED, workspace.path, artifact.path, workspace.target,
                        PublicationState.NOT_PUBLISHED);
                } catch (LineageSidecarStorageException exception) {
                    storageFailure = exception.code();
                } catch (IOException | RuntimeException exception) {
                    storageFailure = Code.FILE_SYNC_FAILED;
                }
            }
        } finally {
            try {
                channel.close();
            } catch (IOException | RuntimeException exception) {
                if (validationFailure == null && storageFailure == null) {
                    storageFailure = Code.WRITE_FAILED;
                }
            }
        }
        if (validationFailure != null) {
            // Re-throw only after close so a raw close IOException can never become suppressed on
            // the public validation exception.
            throw validationFailure;
        }
        if (storageFailure != null) {
            // Always construct a new exception after close; it therefore has no raw cause or
            // suppressed filesystem exception containing a path.
            throw failure(storageFailure, PublicationState.NOT_PUBLISHED);
        }
    }

    private VerifiedSidecar verifyFile(Path path,
                                       Document expected,
                                       VerificationRole role)
            throws LineageSidecarStorageException {
        Code invalidCode = role == VerificationRole.TARGET
                ? Code.TARGET_CONFLICT : Code.TEMP_VERIFICATION_FAILED;
        FileSnapshot before = snapshotRegularFile(
                path, invalidCode, PublicationState.NOT_PUBLISHED);
        MessageDigest digest = sha256();
        Document actual;
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             DigestInputStream input = new DigestInputStream(
                     Channels.newInputStream(channel), digest)) {
            actual = codec.read(input);
        } catch (LineageValidationException exception) {
            throw failure(invalidCode, PublicationState.NOT_PUBLISHED);
        } catch (IOException | RuntimeException exception) {
            throw failure(invalidCode, PublicationState.NOT_PUBLISHED);
        }
        FileSnapshot after = snapshotRegularFile(
                path, invalidCode, PublicationState.NOT_PUBLISHED);
        if (!before.sameFileAndContentSnapshot(after)) {
            throw failure(invalidCode, PublicationState.NOT_PUBLISHED);
        }
        if (before.size > AnalysisAlleleLineageNdjsonCodec.MAX_IN_MEMORY_SIDECAR_BYTES) {
            throw failure(invalidCode, PublicationState.NOT_PUBLISHED);
        }
        if (!isCompletelyBoundToExpected(actual, expected)) {
            throw failure(role == VerificationRole.TARGET
                            ? Code.TARGET_CONFLICT : Code.EXPECTED_DOCUMENT_MISMATCH,
                    PublicationState.NOT_PUBLISHED);
        }
        return new VerifiedSidecar(
                FILE_NAME,
                before.size,
                HexFormat.of().formatHex(digest.digest()),
                actual,
                after);
    }

    private VerifiedSidecar forceAndReverifyTarget(Workspace workspace,
                                                    Document expected,
                                                    VerifiedSidecar accepted,
                                                    PublicationState state)
            throws LineageSidecarStorageException {
        revalidateWorkspace(workspace, state);
        requireUnchangedFile(workspace.target, accepted.snapshot, Code.TARGET_CONFLICT, state);

        Code storageFailure = null;
        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                    workspace.target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            channel.force(true);
        } catch (IOException | RuntimeException exception) {
            storageFailure = Code.FILE_SYNC_FAILED;
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException exception) {
                    if (storageFailure == null) {
                        storageFailure = Code.FILE_SYNC_FAILED;
                    }
                }
            }
        }
        if (storageFailure != null) {
            throw failure(storageFailure, state);
        }

        reach(Stage.TARGET_FILE_FORCED, workspace.path, null, workspace.target, state);
        revalidateWorkspace(workspace, state);
        VerifiedSidecar current = verifyTargetAtState(workspace.target, expected, state);
        requireVerifiedSnapshot(accepted, current, Code.TARGET_CONFLICT, state);
        return current;
    }

    private static void requireVerifiedSnapshot(VerifiedSidecar expected,
                                                VerifiedSidecar actual,
                                                Code code,
                                                PublicationState state)
            throws LineageSidecarStorageException {
        if (expected == null || actual == null
                || !expected.snapshot.sameFileAndContentSnapshot(actual.snapshot)
                || expected.physicalBytes != actual.physicalBytes
                || !expected.physicalSha256.equals(actual.physicalSha256)) {
            throw failure(code, state);
        }
    }

    private PublicationResult finishConcurrentPublication(Workspace workspace,
                                                           Path temporary,
                                                           FileSnapshot identity,
                                                           Document expected)
            throws LineageSidecarStorageException {
        reach(Stage.CONCURRENT_PUBLICATION_DETECTED, workspace.path, temporary,
                workspace.target, PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
        VerifiedSidecar existing = verifyFile(
                workspace.target, expected, VerificationRole.TARGET);
        existing = forceAndReverifyTarget(
                workspace, expected, existing,
                PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
        syncDirectory(workspace, PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
        revalidateWorkspace(workspace, PublicationState.PUBLISHED_DURABLE);
        VerifiedSidecar durable = verifyTargetAtState(
                workspace.target, expected, PublicationState.PUBLISHED_DURABLE);
        requireVerifiedSnapshot(existing, durable, Code.TARGET_CONFLICT,
                PublicationState.PRESENT_DURABILITY_UNCONFIRMED);
        CleanupStatus cleanup = cleanupPublishedTemporary(workspace, temporary, identity);
        revalidateWorkspace(workspace, PublicationState.PUBLISHED_DURABLE);
        VerifiedSidecar current = verifyTargetAtState(
                workspace.target, expected, PublicationState.PUBLISHED_DURABLE);
        requireVerifiedSnapshot(durable, current, Code.TARGET_CONFLICT,
                PublicationState.PUBLISHED_DURABLE);
        return new PublicationResult(
                Outcome.ALREADY_PRESENT_IDENTICAL, current, cleanup);
    }

    private VerifiedSidecar verifyTargetAtState(Path target,
                                                Document expected,
                                                PublicationState state)
            throws LineageSidecarStorageException {
        try {
            return verifyFile(target, expected, VerificationRole.TARGET);
        } catch (LineageSidecarStorageException exception) {
            throw failure(exception.code(), state);
        }
    }

    private CleanupStatus cleanupPublishedTemporary(Workspace workspace,
                                                     Path temporary,
                                                     FileSnapshot identity)
            throws LineageSidecarStorageException {
        if (!cleanupOwnTemporary(temporary, identity)) {
            return CleanupStatus.STALE_TEMP_PRESENT;
        }
        reach(Stage.TEMP_DELETED, workspace.path, temporary, workspace.target,
                PublicationState.PUBLISHED_DURABLE);
        try {
            directorySync.sync(workspace.path, workspace.identity);
            reach(Stage.CLEANUP_DIRECTORY_SYNCED, workspace.path, temporary, workspace.target,
                    PublicationState.PUBLISHED_DURABLE);
            return CleanupStatus.CLEAN;
        } catch (IOException | RuntimeException exception) {
            return CleanupStatus.DELETION_DURABILITY_UNCONFIRMED;
        }
    }

    private boolean cleanupOwnTemporary(Path temporary, FileSnapshot expectedIdentity) {
        if (temporary == null || expectedIdentity == null
                || !expectedIdentity.deletionAuthorized
                || temporary.getFileName() == null
                || !TEMP_NAME.matcher(temporary.getFileName().toString()).matches()) {
            return false;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(temporary)
                    || !expectedIdentity.sameIdentity(
                    identitySource.identity(temporary, attributes))) {
                return false;
            }
            Files.delete(temporary);
            return true;
        } catch (NoSuchFileException exception) {
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void requireSameFile(Path temporary,
                                 Path target,
                                 PublicationState state)
            throws LineageSidecarStorageException {
        try {
            if (!Files.isSameFile(temporary, target)) {
                throw failure(Code.PUBLISH_FAILED, state);
            }
        } catch (LineageSidecarStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.PUBLISH_FAILED, state);
        }
    }

    private FileSnapshot snapshotRegularFile(Path path,
                                             Code code,
                                             PublicationState state)
            throws LineageSidecarStorageException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther() || isReparsePoint(path)) {
                throw failure(code, state);
            }
            Object identity = identitySource.identity(path, attributes);
            if (identity == null) {
                throw failure(Code.FILESYSTEM_UNSUPPORTED, state);
            }
            return new FileSnapshot(identity, identitySource.authorizesDeletion(),
                    attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
        } catch (LineageSidecarStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(code, state);
        }
    }

    private void requireUnchangedFile(Path path,
                                      FileSnapshot expected,
                                      Code code,
                                      PublicationState state)
            throws LineageSidecarStorageException {
        if (expected == null) {
            throw failure(code, state);
        }
        FileSnapshot actual = snapshotRegularFile(path, code, state);
        if (!expected.sameFileAndContentSnapshot(actual)) {
            throw failure(code, state);
        }
    }

    private void requireDirectorySync(Workspace workspace)
            throws LineageSidecarStorageException {
        try {
            directorySync.preflight(workspace.path, workspace.identity);
        } catch (UnsupportedOperationException exception) {
            throw failure(Code.DIRECTORY_SYNC_UNSUPPORTED,
                    PublicationState.NOT_PUBLISHED);
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.DIRECTORY_SYNC_FAILED, PublicationState.NOT_PUBLISHED);
        }
    }

    private void syncDirectory(Workspace workspace, PublicationState state)
            throws LineageSidecarStorageException {
        try {
            directorySync.sync(workspace.path, workspace.identity);
        } catch (UnsupportedOperationException exception) {
            throw failure(Code.DIRECTORY_SYNC_UNSUPPORTED, state);
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.DIRECTORY_SYNC_FAILED, state);
        }
    }

    private void reach(Stage stage,
                       Path workspace,
                       Path temporary,
                       Path target,
                       PublicationState state)
            throws LineageSidecarStorageException {
        try {
            faultHook.reached(stage, workspace, temporary, target);
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.PUBLISH_FAILED, state);
        }
    }

    private boolean existsNoFollow(Path path) throws LineageSidecarStorageException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        } catch (IOException | RuntimeException exception) {
            throw failure(Code.PUBLISH_FAILED, PublicationState.NOT_PUBLISHED);
        }
    }

    private static BasicFileAttributes readBasic(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean fingerprintEqual(Document actual, Document expected) {
        if (actual == null || expected == null || actual.footer() == null
                || expected.footer() == null
                || actual.footer().contentFingerprint() == null
                || expected.footer().contentFingerprint() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.footer().contentFingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expected.footer().contentFingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static boolean isCompletelyBoundToExpected(Document actual, Document expected) {
        return fingerprintEqual(actual, expected) && actual.equals(expected);
    }

    private static boolean isNetworkOrDevicePath(String rawPath) {
        String normalized = rawPath.replace('/', '\\');
        return normalized.startsWith("\\\\")
                || normalized.startsWith("\\\\?\\")
                || normalized.startsWith("\\\\.\\");
    }

    private static boolean isReparsePoint(Path path) throws IOException {
        DosFileAttributeView view = Files.getFileAttributeView(
                path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return false;
        }
        Object value = Files.getAttribute(
                path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
        if (!(value instanceof Number)) {
            throw new IOException();
        }
        return ((((Number) value).intValue()) & 0x400) != 0;
    }

    private static MessageDigest sha256() throws LineageSidecarStorageException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw failure(Code.TEMP_VERIFICATION_FAILED,
                    PublicationState.NOT_PUBLISHED);
        }
    }

    private static DirectorySync platformDirectorySync() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return new DirectorySync() {
                @Override
                public void preflight(Path directory, Object expectedDirectoryIdentity) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void sync(Path directory, Object expectedDirectoryIdentity) {
                    throw new UnsupportedOperationException();
                }
            };
        }
        return new DirectorySync() {
            @Override
            public void preflight(Path directory, Object expectedDirectoryIdentity)
                    throws IOException {
                sync(directory, expectedDirectoryIdentity);
            }

            @Override
            public void sync(Path directory, Object expectedDirectoryIdentity)
                    throws IOException {
                requireDirectoryIdentity(directory, expectedDirectoryIdentity);
                try (FileChannel channel = FileChannel.open(
                        directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    requireDirectoryIdentity(directory, expectedDirectoryIdentity);
                    channel.force(true);
                    requireDirectoryIdentity(directory, expectedDirectoryIdentity);
                }
            }
        };
    }

    private static void requireDirectoryIdentity(Path directory,
                                                 Object expectedDirectoryIdentity)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (expectedDirectoryIdentity == null || attributes.fileKey() == null
                || !expectedDirectoryIdentity.equals(attributes.fileKey())
                || !attributes.isDirectory() || attributes.isSymbolicLink()
                || attributes.isOther() || isReparsePoint(directory)) {
            throw new IOException();
        }
    }

    private static LineageSidecarStorageException failure(Code code,
                                                           PublicationState state) {
        return new LineageSidecarStorageException(code, state);
    }

    interface DirectorySync {
        void preflight(Path directory, Object expectedDirectoryIdentity) throws IOException;

        void sync(Path directory, Object expectedDirectoryIdentity) throws IOException;
    }

    interface PublishFaultHook {
        PublishFaultHook NOOP = (stage, workspace, temporary, target) -> { };

        void reached(Stage stage, Path workspace, Path temporary, Path target) throws IOException;
    }

    interface FileIdentitySource {
        FileIdentitySource STRICT = new FileIdentitySource() {
            @Override
            public Object identity(Path path, BasicFileAttributes attributes) {
                return attributes.fileKey();
            }

            @Override
            public boolean authorizesDeletion() {
                // Only a non-null provider fileKey is accepted by snapshotRegularFile. This flag
                // therefore never authorizes deletion based on a path, timestamp, or file name.
                return true;
            }
        };

        Object identity(Path path, BasicFileAttributes attributes) throws IOException;

        boolean authorizesDeletion();
    }

    enum Stage {
        WORKSPACE_VALIDATED,
        TEMP_CREATED,
        WRITE_COMPLETED,
        FILE_FORCED,
        FILE_CLOSED,
        TEMP_VERIFIED,
        BEFORE_PUBLISH,
        CONCURRENT_PUBLICATION_DETECTED,
        TARGET_LINKED,
        TARGET_VERIFIED,
        TARGET_FILE_FORCED,
        DIRECTORY_SYNCED,
        TEMP_DELETED,
        CLEANUP_DIRECTORY_SYNCED
    }

    enum Outcome {
        CREATED,
        ALREADY_PRESENT_IDENTICAL
    }

    enum CleanupStatus {
        CLEAN,
        STALE_TEMP_PRESENT,
        DELETION_DURABILITY_UNCONFIRMED
    }

    static final class PublicationResult {
        private final Outcome outcome;
        private final VerifiedSidecar verified;
        private final CleanupStatus cleanupStatus;

        private PublicationResult(Outcome outcome,
                                  VerifiedSidecar verified,
                                  CleanupStatus cleanupStatus) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.verified = Objects.requireNonNull(verified, "verified");
            this.cleanupStatus = Objects.requireNonNull(cleanupStatus, "cleanupStatus");
        }

        Outcome outcome() {
            return outcome;
        }

        VerifiedSidecar verified() {
            return verified;
        }

        CleanupStatus cleanupStatus() {
            return cleanupStatus;
        }

        String logicalFileName() {
            return FILE_NAME;
        }

        @Override
        public String toString() {
            return "PublicationResult[redacted]";
        }
    }

    static final class VerifiedSidecar {
        private final String logicalFileName;
        private final long physicalBytes;
        private final String physicalSha256;
        private final Document document;
        private final FileSnapshot snapshot;

        private VerifiedSidecar(String logicalFileName,
                                long physicalBytes,
                                String physicalSha256,
                                Document document,
                                FileSnapshot snapshot) {
            this.logicalFileName = logicalFileName;
            this.physicalBytes = physicalBytes;
            this.physicalSha256 = physicalSha256;
            this.document = document;
            this.snapshot = snapshot;
        }

        String logicalFileName() {
            return logicalFileName;
        }

        long physicalBytes() {
            return physicalBytes;
        }

        String physicalSha256() {
            return physicalSha256;
        }

        Document document() {
            return document;
        }

        @Override
        public String toString() {
            return "VerifiedSidecar[redacted]";
        }
    }

    private enum VerificationRole {
        TEMPORARY,
        TARGET
    }

    private static final class Workspace {
        private final Path path;
        private final Path target;
        private final Object identity;

        private Workspace(Path path, Path target, Object identity) {
            this.path = path;
            this.target = target;
            this.identity = identity;
        }
    }

    private static final class TempArtifact {
        private final Path path;
        private final FileChannel channel;
        private final FileSnapshot identity;

        private TempArtifact(Path path, FileChannel channel, FileSnapshot identity) {
            this.path = path;
            this.channel = channel;
            this.identity = identity;
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

        private boolean sameIdentity(Object otherIdentity) {
            return deletionAuthorized && identity != null && otherIdentity != null
                    && identity.equals(otherIdentity);
        }

        private boolean sameFileAndContentSnapshot(FileSnapshot other) {
            boolean identityMatches = identity != null && other.identity != null
                    && identity.equals(other.identity);
            return identityMatches && size == other.size && modified.equals(other.modified)
                    && created.equals(other.created);
        }
    }
}
