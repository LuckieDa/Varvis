package org.nbscn.varvis.variantanalysis.exomiser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Performs one-pass, bounded framing, validation, hashing, and row delivery for Exomiser TSVs.
 *
 * <p>The production ceilings deliberately reflect the current downstream double-allocation path:
 * the parser materializes every {@code VariantInterpretation} and copies the complete source row
 * into evidence details, while the adapter also builds candidate keys, indexes, and hydrated
 * lists. They are a fail-closed in-memory safety ceiling, not a capacity commitment or a statement
 * that arbitrary TSV files of this size are generally safe to retain in memory. They may only be
 * raised after a fixed-Xmx end-to-end heap benchmark. This scanner itself retains only one bounded
 * physical line and, during {@link #scan(Path, ArtifactKind, RowConsumer)}, one row map at a time.</p>
 *
 * <p>When the provider supplies a file key, a successful result binds the raw digest and counters
 * to that regular, non-symbolic file, plus its size, modification time, and creation time, before
 * the single {@link FileChannel} open, immediately after it, and after EOF. The OpenJDK-derived
 * Windows default provider currently returns a null file key; for a drive-letter NTFS path, the
 * scanner can instead use a conservative drift token made from the provider-specific volume
 * serial number, canonical no-follow path, and creation time. That token is not a native file ID.
 * The caller must ensure that this fallback path is locally mounted and product-private; public
 * Java APIs cannot reliably distinguish every mapped drive. These checks detect non-adversarial
 * drift. Malicious same-UID/same-permission metadata-replicating ABA replacement is outside this
 * Java path-based boundary.</p>
 */
public final class ExomiserTsvScanner {

    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_LINE_BYTES = 256 * 1024;

    private static final Limits VARIANT_LIMITS = new Limits(
            128L * 1024L * 1024L,
            100_001L,
            50_000L,
            MAX_COLUMNS,
            MAX_LINE_BYTES);
    private static final Limits GENE_LIMITS = new Limits(
            64L * 1024L * 1024L,
            100_001L,
            50_000L,
            MAX_COLUMNS,
            MAX_LINE_BYTES);

    private final Limits variantLimits;
    private final Limits geneLimits;
    private final FileIdentitySource identitySource;
    private final ReparsePointSource reparsePointSource;
    private final RowMapObserver rowMapObserver;

    public ExomiserTsvScanner() {
        this(VARIANT_LIMITS, GENE_LIMITS, FileIdentitySource.PRODUCT_PRIVATE,
                ReparsePointSource.DOS_WHEN_AVAILABLE, RowMapObserver.NOOP);
    }

    ExomiserTsvScanner(FileIdentitySource identitySource) {
        this(VARIANT_LIMITS, GENE_LIMITS, identitySource,
                ReparsePointSource.DOS_WHEN_AVAILABLE, RowMapObserver.NOOP);
    }

    ExomiserTsvScanner(FileIdentitySource identitySource, RowMapObserver rowMapObserver) {
        this(VARIANT_LIMITS, GENE_LIMITS, identitySource,
                ReparsePointSource.DOS_WHEN_AVAILABLE, rowMapObserver);
    }

    ExomiserTsvScanner(FileIdentitySource identitySource,
                       ReparsePointSource reparsePointSource) {
        this(VARIANT_LIMITS, GENE_LIMITS, identitySource,
                reparsePointSource, RowMapObserver.NOOP);
    }

    ExomiserTsvScanner(Limits limits, FileIdentitySource identitySource) {
        this(limits, limits, identitySource,
                ReparsePointSource.DOS_WHEN_AVAILABLE, RowMapObserver.NOOP);
    }

    ExomiserTsvScanner(Limits variantLimits,
                       Limits geneLimits,
                       FileIdentitySource identitySource,
                       ReparsePointSource reparsePointSource,
                       RowMapObserver rowMapObserver) {
        this.variantLimits = Objects.requireNonNull(variantLimits, "variantLimits");
        this.geneLimits = Objects.requireNonNull(geneLimits, "geneLimits");
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
        this.reparsePointSource = Objects.requireNonNull(
                reparsePointSource, "reparsePointSource");
        this.rowMapObserver = Objects.requireNonNull(rowMapObserver, "rowMapObserver");
    }

    /** Scans metadata without retaining or delivering row maps. */
    public ScanResult inspect(Path artifact, ArtifactKind kind) throws ScanException {
        return scanInternal(artifact, kind, null, false);
    }

    /**
     * Performs a metadata-only required-artifact preflight without opening its content.
     * A later scan remains authoritative and detects any intervening drift.
     */
    public void verifyRequiredNonEmpty(Path artifact) throws ScanException {
        if (artifact == null) {
            throw new ScanException(Code.PATH_REQUIRED);
        }
        try {
            FileSnapshot current = snapshot(
                    artifact, Code.FILE_MISSING, Code.FILE_INVALID,
                    Code.FILESYSTEM_UNSUPPORTED);
            if (current.size == 0L) {
                throw new Failure(Code.HEADER_MISSING);
            }
        } catch (Failure exception) {
            throw new ScanException(exception.code);
        }
    }

    /**
     * Scans once and delivers each nonblank data row in source order.
     *
     * <p>Each callback receives a new insertion-ordered, unmodifiable map. Missing optional tail
     * cells are represented by empty strings. The map may be retained by the callback, although
     * doing so transfers the memory-bound responsibility to the caller. Rows are delivered before
     * the final path snapshot is verified, so the callback must stage only reversible local state;
     * it must not publish or persist externally visible effects.</p>
     */
    public ScanResult scan(Path artifact, ArtifactKind kind, RowConsumer consumer)
            throws ScanException {
        return scanInternal(artifact, kind, consumer, true);
    }

    /**
     * Hashes one opaque companion artifact through a single bounded content open.
     *
     * <p>No text decoding or row parsing is performed. The returned facts retain an opaque
     * snapshot that can later be passed to {@link #verifyRawUnchanged(Path, RawScanResult)}.</p>
     */
    public RawScanResult inspectRaw(Path artifact, long maxTotalBytes) throws ScanException {
        if (artifact == null) {
            throw new ScanException(Code.PATH_REQUIRED);
        }
        if (maxTotalBytes <= 0L) {
            throw new ScanException(Code.INVALID_LIMITS);
        }

        FileChannel channel = null;
        RawScanResult result = null;
        Code failure = null;
        try {
            FileSnapshot before = snapshot(
                    artifact, Code.FILE_MISSING, Code.FILE_INVALID,
                    Code.FILESYSTEM_UNSUPPORTED);
            if (before.size > maxTotalBytes) {
                throw new Failure(Code.TOTAL_BYTES_LIMIT_EXCEEDED);
            }

            Set<OpenOption> options = Set.of(
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
            channel = FileChannel.open(artifact, options);
            FileSnapshot afterOpen = snapshot(
                    artifact, Code.FILE_CHANGED, Code.FILE_CHANGED, Code.FILE_CHANGED);
            if (!before.same(afterOpen) || channel.size() != before.size) {
                throw new Failure(Code.FILE_CHANGED);
            }

            result = scanRawChannel(channel, maxTotalBytes, before);

            FileSnapshot afterRead = snapshot(
                    artifact, Code.FILE_CHANGED, Code.FILE_CHANGED, Code.FILE_CHANGED);
            if (!before.same(afterRead)
                    || channel.size() != before.size
                    || channel.position() != before.size
                    || result.byteCount() != before.size) {
                throw new Failure(Code.FILE_CHANGED);
            }
        } catch (Failure exception) {
            failure = exception.code;
        } catch (NoSuchFileException exception) {
            failure = Code.FILE_CHANGED;
        } catch (UnsupportedOperationException exception) {
            failure = Code.FILESYSTEM_UNSUPPORTED;
        } catch (IOException | RuntimeException exception) {
            failure = Code.READ_FAILED;
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException exception) {
                    if (failure == null) {
                        failure = Code.READ_FAILED;
                    }
                }
            }
        }
        if (failure != null) {
            throw new ScanException(failure);
        }
        return result;
    }

    /** Verifies that a TSV path still names the snapshot represented by a prior scan. */
    public void verifyUnchanged(Path artifact, ScanResult expected) throws ScanException {
        verifySnapshot(artifact, expected == null ? null : expected.verificationSnapshot);
    }

    /** Verifies that an opaque companion path still names the snapshot represented by a prior scan. */
    public void verifyRawUnchanged(Path artifact, RawScanResult expected) throws ScanException {
        verifySnapshot(artifact, expected == null ? null : expected.verificationSnapshot);
    }

    /** Verifies that an optional artifact which was previously absent is still absent. */
    public void verifyAbsent(Path artifact) throws ScanException {
        if (artifact == null) {
            throw new ScanException(Code.PATH_REQUIRED);
        }
        try {
            snapshot(artifact, Code.FILE_MISSING, Code.FILE_CHANGED, Code.FILE_CHANGED);
        } catch (Failure exception) {
            if (exception.code == Code.FILE_MISSING) {
                return;
            }
            throw new ScanException(exception.code);
        }
        throw new ScanException(Code.FILE_CHANGED);
    }

    private void verifySnapshot(Path artifact, FileSnapshot expected) throws ScanException {
        if (artifact == null) {
            throw new ScanException(Code.PATH_REQUIRED);
        }
        if (expected == null) {
            throw new ScanException(Code.SCAN_RESULT_REQUIRED);
        }
        try {
            FileSnapshot current = snapshot(
                    artifact, Code.FILE_CHANGED, Code.FILE_CHANGED, Code.FILE_CHANGED);
            if (!expected.same(current)) {
                throw new Failure(Code.FILE_CHANGED);
            }
        } catch (Failure exception) {
            throw new ScanException(exception.code);
        }
    }

    private ScanResult scanInternal(Path artifact,
                                    ArtifactKind kind,
                                    RowConsumer consumer,
                                    boolean deliverRows) throws ScanException {
        if (artifact == null) {
            throw new ScanException(Code.PATH_REQUIRED);
        }
        if (kind == null) {
            throw new ScanException(Code.KIND_REQUIRED);
        }
        if (deliverRows && consumer == null) {
            throw new ScanException(Code.CONSUMER_REQUIRED);
        }

        Limits limits = kind == ArtifactKind.VARIANTS ? variantLimits : geneLimits;
        FileChannel channel = null;
        ScanResult result = null;
        Code failure = null;
        try {
            FileSnapshot before = snapshot(
                    artifact, Code.FILE_MISSING, Code.FILE_INVALID,
                    Code.FILESYSTEM_UNSUPPORTED);
            if (before.size > limits.maxTotalBytes) {
                throw new Failure(Code.TOTAL_BYTES_LIMIT_EXCEEDED);
            }

            Set<OpenOption> options = Set.of(
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
            channel = FileChannel.open(artifact, options);
            FileSnapshot afterOpen = snapshot(
                    artifact, Code.FILE_CHANGED, Code.FILE_CHANGED, Code.FILE_CHANGED);
            if (!before.same(afterOpen) || channel.size() != before.size) {
                throw new Failure(Code.FILE_CHANGED);
            }

            result = scanChannel(channel, kind, limits, consumer, deliverRows, before);

            FileSnapshot afterRead = snapshot(
                    artifact, Code.FILE_CHANGED, Code.FILE_CHANGED, Code.FILE_CHANGED);
            if (!before.same(afterRead)
                    || channel.size() != before.size
                    || channel.position() != before.size
                    || result.byteCount() != before.size) {
                throw new Failure(Code.FILE_CHANGED);
            }
        } catch (Failure exception) {
            failure = exception.code;
        } catch (NoSuchFileException exception) {
            // The initial snapshot already proved that the entry existed. Disappearance while
            // opening the sole channel is mutation, never an optional-artifact absence.
            failure = Code.FILE_CHANGED;
        } catch (UnsupportedOperationException exception) {
            failure = Code.FILESYSTEM_UNSUPPORTED;
        } catch (IOException | RuntimeException exception) {
            failure = Code.READ_FAILED;
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException exception) {
                    if (failure == null) {
                        failure = Code.READ_FAILED;
                    }
                }
            }
        }
        if (failure != null) {
            // Construct only after close. Raw filesystem exceptions can therefore never become a
            // cause or a suppressed exception containing an absolute path.
            throw new ScanException(failure);
        }
        return result;
    }

    private ScanResult scanChannel(FileChannel channel,
                                   ArtifactKind kind,
                                   Limits limits,
                                   RowConsumer consumer,
                                   boolean deliverRows,
                                   FileSnapshot snapshot) throws IOException, Failure {
        MessageDigest digest = sha256();
        LineProcessor lines = new LineProcessor(
                kind, limits, consumer, deliverRows, rowMapObserver);
        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
        long byteCount = 0L;
        while (true) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            buffer.flip();
            int chunkBytes = buffer.remaining();
            if (byteCount > limits.maxTotalBytes - chunkBytes) {
                throw new Failure(Code.TOTAL_BYTES_LIMIT_EXCEEDED);
            }
            digest.update(buffer.asReadOnlyBuffer());
            byteCount += chunkBytes;
            while (buffer.hasRemaining()) {
                lines.accept(buffer.get() & 0xff);
            }
            buffer.clear();
        }
        lines.finish();
        return new ScanResult(
                HexFormat.of().formatHex(digest.digest()),
                byteCount,
                lines.physicalLineCount,
                lines.dataRowCount,
                lines.header.size(),
                lines.header,
                snapshot);
    }

    private RawScanResult scanRawChannel(FileChannel channel,
                                         long maxTotalBytes,
                                         FileSnapshot snapshot) throws IOException, Failure {
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
        long byteCount = 0L;
        while (true) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            buffer.flip();
            int chunkBytes = buffer.remaining();
            if (byteCount > maxTotalBytes - chunkBytes) {
                throw new Failure(Code.TOTAL_BYTES_LIMIT_EXCEEDED);
            }
            digest.update(buffer.asReadOnlyBuffer());
            byteCount += chunkBytes;
            buffer.position(buffer.limit());
            buffer.clear();
        }
        return new RawScanResult(
                HexFormat.of().formatHex(digest.digest()),
                byteCount,
                snapshot);
    }

    private FileSnapshot snapshot(Path artifact,
                                  Code missingFailure,
                                  Code ioFailure,
                                  Code identityFailure) throws Failure {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    artifact, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || reparsePointSource.isReparsePoint(artifact)) {
                throw new Failure(ioFailure);
            }
            Object identity = identitySource.identity(artifact, attributes);
            if (identity == null) {
                throw new Failure(identityFailure);
            }
            IdentityAssurance assurance = identity instanceof WindowsPrivatePathDiscriminator
                    ? IdentityAssurance.WINDOWS_PRIVATE_PATH_DRIFT_TOKEN
                    : IdentityAssurance.PROVIDER_FILE_KEY;
            return new FileSnapshot(
                    identity,
                    assurance,
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.creationTime());
        } catch (Failure exception) {
            throw exception;
        } catch (NoSuchFileException exception) {
            throw new Failure(missingFailure);
        } catch (IOException | RuntimeException exception) {
            throw new Failure(ioFailure);
        }
    }

    private static MessageDigest sha256() throws Failure {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new Failure(Code.HASH_UNAVAILABLE);
        }
    }

    private static boolean isDosReparsePoint(Path path) throws IOException {
        DosFileAttributeView view = Files.getFileAttributeView(
                path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return false;
        }
        final Object rawAttributes;
        try {
            rawAttributes = Files.getAttribute(
                    path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            // Some regular POSIX providers advertise a DOS view without exposing the Windows raw
            // attribute word. Absence of that optional capability is not evidence of a reparse
            // point and must not reject an otherwise regular POSIX file.
            return false;
        }
        if (!(rawAttributes instanceof Number)) {
            throw new IOException();
        }
        return ((((Number) rawAttributes).intValue()) & 0x400) != 0;
    }

    private static Object productPrivateFileDiscriminator(Path path,
                                                          BasicFileAttributes attributes)
            throws IOException {
        Object providerKey = attributes.fileKey();
        if (providerKey != null) {
            return providerKey;
        }

        Path canonical = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path root = canonical.getRoot();
        String canonicalText = canonical.toString();
        String rootText = root == null ? "" : root.toString();
        if (root == null
                || isNetworkOrDevicePath(canonical.toString())
                || !rootText.matches("(?i)^[a-z]:\\\\$")
                || canonicalText.indexOf(':', 2) >= 0
                || !path.getFileSystem().equals(FileSystems.getDefault())
                || !"file".equalsIgnoreCase(path.getFileSystem().provider().getScheme())) {
            return null;
        }
        FileStore store = Files.getFileStore(canonical);
        if (!"NTFS".equalsIgnoreCase(store.type())) {
            return null;
        }
        final Object rawVolumeSerial;
        try {
            rawVolumeSerial = store.getAttribute("volume:vsn");
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return null;
        }
        if (!(rawVolumeSerial instanceof Number)) {
            return null;
        }
        long volumeSerial = ((Number) rawVolumeSerial).longValue() & 0xffff_ffffL;
        if (volumeSerial == 0L || attributes.creationTime() == null
                || attributes.creationTime().toMillis() <= 0L) {
            return null;
        }
        return new WindowsPrivatePathDiscriminator(
                volumeSerial,
                canonical,
                attributes.creationTime());
    }

    private static boolean isNetworkOrDevicePath(String rawPath) {
        String normalized = rawPath.replace('/', '\\');
        return normalized.startsWith("\\\\")
                || normalized.startsWith("\\\\?\\")
                || normalized.startsWith("\\\\.\\");
    }

    public enum ArtifactKind {
        VARIANTS(List.of("#RANK", "CONTIG", "START", "REF", "ALT")),
        GENES(List.of("#RANK")),
        JSONL(List.of());

        private final List<String> requiredHeaders;

        ArtifactKind(List<String> requiredHeaders) {
            this.requiredHeaders = requiredHeaders;
        }
    }

    /** Stable TSV scan metadata; no data rows or printable path tokens are retained here. */
    public static final class ScanResult {
        private final String rawSha256;
        private final long byteCount;
        private final long physicalLineCount;
        private final long dataRowCount;
        private final int columnCount;
        private final List<String> header;
        private final FileSnapshot verificationSnapshot;

        private ScanResult(String rawSha256,
                           long byteCount,
                           long physicalLineCount,
                           long dataRowCount,
                           int columnCount,
                           List<String> header,
                           FileSnapshot verificationSnapshot) {
            this.rawSha256 = Objects.requireNonNull(rawSha256, "rawSha256");
            this.byteCount = byteCount;
            this.physicalLineCount = physicalLineCount;
            this.dataRowCount = dataRowCount;
            this.columnCount = columnCount;
            this.header = List.copyOf(Objects.requireNonNull(header, "header"));
            this.verificationSnapshot = Objects.requireNonNull(
                    verificationSnapshot, "verificationSnapshot");
        }

        public String rawSha256() {
            return rawSha256;
        }

        public long byteCount() {
            return byteCount;
        }

        public long physicalLineCount() {
            return physicalLineCount;
        }

        public long dataRowCount() {
            return dataRowCount;
        }

        public int columnCount() {
            return columnCount;
        }

        public List<String> header() {
            return header;
        }

        public IdentityAssurance identityAssurance() {
            return verificationSnapshot.assurance;
        }

        @Override
        public String toString() {
            return "ScanResult[redacted]";
        }
    }

    /** Stable opaque-artifact facts; the verification snapshot is intentionally not exposed. */
    public static final class RawScanResult {
        private final String rawSha256;
        private final long byteCount;
        private final FileSnapshot verificationSnapshot;

        private RawScanResult(String rawSha256,
                              long byteCount,
                              FileSnapshot verificationSnapshot) {
            this.rawSha256 = Objects.requireNonNull(rawSha256, "rawSha256");
            this.byteCount = byteCount;
            this.verificationSnapshot = Objects.requireNonNull(
                    verificationSnapshot, "verificationSnapshot");
        }

        public String rawSha256() {
            return rawSha256;
        }

        public long byteCount() {
            return byteCount;
        }

        public IdentityAssurance identityAssurance() {
            return verificationSnapshot.assurance;
        }

        @Override
        public String toString() {
            return "RawScanResult[redacted]";
        }
    }

    /** Declares which non-printable discriminator backed a successful scan. */
    public enum IdentityAssurance {
        PROVIDER_FILE_KEY,
        WINDOWS_PRIVATE_PATH_DRIFT_TOKEN
    }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(Map<String, String> row, int dataRowOrdinal);
    }

    public enum Code {
        PATH_REQUIRED,
        KIND_REQUIRED,
        CONSUMER_REQUIRED,
        SCAN_RESULT_REQUIRED,
        INVALID_LIMITS,
        FILE_MISSING,
        FILE_INVALID,
        FILESYSTEM_UNSUPPORTED,
        FILE_CHANGED,
        READ_FAILED,
        HASH_UNAVAILABLE,
        TOTAL_BYTES_LIMIT_EXCEEDED,
        PHYSICAL_LINE_LIMIT_EXCEEDED,
        DATA_ROW_LIMIT_EXCEEDED,
        LINE_BYTES_LIMIT_EXCEEDED,
        COLUMN_LIMIT_EXCEEDED,
        BOM_FORBIDDEN,
        NUL_FORBIDDEN,
        BARE_CR_FORBIDDEN,
        INVALID_UTF8,
        HEADER_MISSING,
        HEADER_BLANK,
        HEADER_DUPLICATE,
        REQUIRED_HEADER_MISSING,
        TSV_COLUMN_COUNT_MISMATCH,
        REQUIRED_CELL_MISSING,
        CONSUMER_FAILED
    }

    /** Sanitized scanner failure containing no path, raw value, cause, or suppressed exception. */
    public static final class ScanException extends IOException {
        private final Code code;

        private ScanException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }

        @Override
        public String toString() {
            return "ExomiserTsvScanException[" + code.name() + "]";
        }
    }

    static final class Limits {
        private final long maxTotalBytes;
        private final long maxPhysicalLines;
        private final long maxDataRows;
        private final int maxColumns;
        private final int maxLineBytes;

        Limits(long maxTotalBytes,
               long maxPhysicalLines,
               long maxDataRows,
               int maxColumns,
               int maxLineBytes) {
            if (maxTotalBytes <= 0L || maxPhysicalLines <= 0L || maxDataRows <= 0L
                    || maxColumns <= 0 || maxLineBytes <= 0) {
                throw new IllegalArgumentException("INVALID_LIMITS");
            }
            this.maxTotalBytes = maxTotalBytes;
            this.maxPhysicalLines = maxPhysicalLines;
            this.maxDataRows = maxDataRows;
            this.maxColumns = maxColumns;
            this.maxLineBytes = maxLineBytes;
        }
    }

    interface FileIdentitySource {
        FileIdentitySource PRODUCT_PRIVATE =
                ExomiserTsvScanner::productPrivateFileDiscriminator;

        Object identity(Path path, BasicFileAttributes attributes) throws IOException;
    }

    private static final class WindowsPrivatePathDiscriminator {
        private final long volumeSerial;
        private final Path canonicalPath;
        private final FileTime creationTime;

        private WindowsPrivatePathDiscriminator(long volumeSerial,
                                                Path canonicalPath,
                                                FileTime creationTime) {
            this.volumeSerial = volumeSerial;
            this.canonicalPath = canonicalPath;
            this.creationTime = creationTime;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WindowsPrivatePathDiscriminator)) {
                return false;
            }
            WindowsPrivatePathDiscriminator that = (WindowsPrivatePathDiscriminator) other;
            return volumeSerial == that.volumeSerial
                    && canonicalPath.equals(that.canonicalPath)
                    && creationTime.equals(that.creationTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(volumeSerial, canonicalPath, creationTime);
        }

        @Override
        public String toString() {
            return "WindowsPrivatePathDiscriminator[redacted]";
        }
    }

    interface ReparsePointSource {
        ReparsePointSource DOS_WHEN_AVAILABLE = ExomiserTsvScanner::isDosReparsePoint;

        boolean isReparsePoint(Path path) throws IOException;
    }

    interface RowMapObserver {
        RowMapObserver NOOP = () -> { };

        void created();
    }

    private static final class LineProcessor {
        private final ArtifactKind kind;
        private final Limits limits;
        private final RowConsumer consumer;
        private final boolean deliverRows;
        private final RowMapObserver rowMapObserver;
        private final byte[] payload;
        private int payloadLength;
        private boolean pendingCarriageReturn;
        private long physicalLineCount;
        private long dataRowCount;
        private List<String> header;
        private int[] requiredColumnIndexes;

        private LineProcessor(ArtifactKind kind,
                              Limits limits,
                              RowConsumer consumer,
                              boolean deliverRows,
                              RowMapObserver rowMapObserver) {
            this.kind = kind;
            this.limits = limits;
            this.consumer = consumer;
            this.deliverRows = deliverRows;
            this.rowMapObserver = rowMapObserver;
            this.payload = new byte[limits.maxLineBytes];
            if (kind == ArtifactKind.JSONL) this.header = List.of("JSONL");
        }

        private void accept(int value) throws Failure {
            if (pendingCarriageReturn) {
                if (value != '\n') {
                    throw new Failure(Code.BARE_CR_FORBIDDEN);
                }
                pendingCarriageReturn = false;
                completePhysicalLine();
                return;
            }
            if (value == '\r') {
                pendingCarriageReturn = true;
                return;
            }
            if (value == '\n') {
                completePhysicalLine();
                return;
            }
            if (value == 0) {
                throw new Failure(Code.NUL_FORBIDDEN);
            }
            if (payloadLength == payload.length) {
                throw new Failure(Code.LINE_BYTES_LIMIT_EXCEEDED);
            }
            payload[payloadLength++] = (byte) value;
        }

        private void finish() throws Failure {
            if (pendingCarriageReturn) {
                throw new Failure(Code.BARE_CR_FORBIDDEN);
            }
            if (payloadLength > 0) {
                completePhysicalLine();
            }
            if (header == null) {
                throw new Failure(Code.HEADER_MISSING);
            }
        }

        private void completePhysicalLine() throws Failure {
            if (physicalLineCount == limits.maxPhysicalLines) {
                throw new Failure(Code.PHYSICAL_LINE_LIMIT_EXCEEDED);
            }
            physicalLineCount++;
            if (physicalLineCount == 1L && startsWithUtf8Bom()) {
                throw new Failure(Code.BOM_FORBIDDEN);
            }
            String line = decodePayload();
            payloadLength = 0;
            if (kind == ArtifactKind.JSONL) {
                if (line.isBlank()) throw new Failure(Code.REQUIRED_CELL_MISSING);
                if (dataRowCount == limits.maxDataRows) throw new Failure(Code.DATA_ROW_LIMIT_EXCEEDED);
                dataRowCount++;
                if (deliverRows) {
                    try { consumer.accept(Map.of("JSONL", line), (int) dataRowCount); }
                    catch (RuntimeException invalid) { throw new Failure(Code.CONSUMER_FAILED); }
                }
            } else if (header == null) {
                parseHeader(line);
            } else if (!line.trim().isEmpty()) {
                parseDataRow(line);
            }
        }

        private boolean startsWithUtf8Bom() {
            return payloadLength >= 3
                    && (payload[0] & 0xff) == 0xef
                    && (payload[1] & 0xff) == 0xbb
                    && (payload[2] & 0xff) == 0xbf;
        }

        private String decodePayload() throws Failure {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(payload, 0, payloadLength))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new Failure(Code.INVALID_UTF8);
            }
        }

        private void parseHeader(String line) throws Failure {
            if (line.isBlank()) {
                throw new Failure(Code.HEADER_BLANK);
            }
            String[] cells = line.split("\\t", -1);
            if (cells.length > limits.maxColumns) {
                throw new Failure(Code.COLUMN_LIMIT_EXCEEDED);
            }
            Set<String> unique = new HashSet<>();
            List<String> columns = new ArrayList<>(cells.length);
            for (String cell : cells) {
                if (cell.isBlank()) {
                    throw new Failure(Code.HEADER_BLANK);
                }
                if (!unique.add(cell)) {
                    throw new Failure(Code.HEADER_DUPLICATE);
                }
                columns.add(cell);
            }
            int[] required = new int[kind.requiredHeaders.size()];
            for (int index = 0; index < kind.requiredHeaders.size(); index++) {
                int columnIndex = columns.indexOf(kind.requiredHeaders.get(index));
                if (columnIndex < 0) {
                    throw new Failure(Code.REQUIRED_HEADER_MISSING);
                }
                required[index] = columnIndex;
            }
            header = List.copyOf(columns);
            requiredColumnIndexes = required;
        }

        private void parseDataRow(String line) throws Failure {
            if (dataRowCount == limits.maxDataRows) {
                throw new Failure(Code.DATA_ROW_LIMIT_EXCEEDED);
            }
            String[] cells = line.split("\\t", -1);
            if (cells.length > header.size()) {
                throw new Failure(Code.TSV_COLUMN_COUNT_MISMATCH);
            }
            for (int requiredIndex : requiredColumnIndexes) {
                if (requiredIndex >= cells.length || cells[requiredIndex].isBlank()) {
                    throw new Failure(Code.REQUIRED_CELL_MISSING);
                }
            }
            dataRowCount++;
            if (!deliverRows) {
                return;
            }
            rowMapObserver.created();
            Map<String, String> row = new LinkedHashMap<>(header.size());
            for (int index = 0; index < header.size(); index++) {
                row.put(header.get(index), index < cells.length ? cells[index] : "");
            }
            try {
                consumer.accept(Collections.unmodifiableMap(row), (int) dataRowCount);
            } catch (RuntimeException exception) {
                throw new Failure(Code.CONSUMER_FAILED);
            }
        }
    }

    private static final class FileSnapshot {
        private final Object identity;
        private final IdentityAssurance assurance;
        private final long size;
        private final FileTime modified;
        private final FileTime created;

        private FileSnapshot(Object identity,
                             IdentityAssurance assurance,
                             long size,
                             FileTime modified,
                             FileTime created) {
            this.identity = identity;
            this.assurance = assurance;
            this.size = size;
            this.modified = modified;
            this.created = created;
        }

        private boolean same(FileSnapshot other) {
            return other != null
                    && identity.equals(other.identity)
                    && assurance == other.assurance
                    && size == other.size
                    && modified.equals(other.modified)
                    && created.equals(other.created);
        }
    }

    private static final class Failure extends Exception {
        private final Code code;

        private Failure(Code code) {
            this.code = code;
        }
    }
}
